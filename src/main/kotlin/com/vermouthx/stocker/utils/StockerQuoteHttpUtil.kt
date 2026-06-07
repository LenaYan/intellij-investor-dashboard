package com.vermouthx.stocker.utils

import com.intellij.openapi.diagnostic.Logger
import com.vermouthx.stocker.entities.StockerIntradayData
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import org.apache.http.client.methods.HttpGet
import org.apache.http.util.EntityUtils

object StockerQuoteHttpUtil {

    private val log = Logger.getInstance(javaClass)
    private val httpClientPool = StockerHttpClientPool(log)

    /**
     * Build the full prefixed code for an A-share symbol.
     * If the code already has sh/sz/bj prefix, return as-is (lowercased).
     * Otherwise, determine prefix: Shanghai (sh) for codes starting with 6, 9, 5;
     * Beijing (bj) for codes starting with 8, 4 (or 9 with length considerations);
     * Shenzhen (sz) for all others.
     */
    fun prefixedAShareCode(code: String): String {
        val lower = code.lowercase()
        if (lower.startsWith("sh") || lower.startsWith("sz") || lower.startsWith("bj")) {
            return lower
        }
        val prefix = when (lower[0]) {
            '6' -> "sh"
            '0', '1', '2', '3' -> "sz"
            '8', '4' -> "bj"
            '9' -> if (lower.startsWith("92") || lower.startsWith("93")) "bj" else "sh"
            '5' -> "sh"
            else -> "sz"
        }
        return "$prefix$lower"
    }

    /**
     * Canonical "SH600519" / "SZ000001" / "BJ430090" form. Used as the stable row identity
     * (and storage key) so that SH000001 (上证指数) and SZ000001 (平安银行) — which share a
     * bare 6-digit code — don't collide in the table. The cell renderer strips this prefix
     * for display, but everything else (settings, fetch, delete) routes by canonical code.
     */
    fun canonicalAShareCode(code: String): String = prefixedAShareCode(code).uppercase()

    fun closeConnections() {
        httpClientPool.close()
    }

    /**
     * Build the provider-specific URL fragment for a single symbol.
     * A-share codes always carry sh/sz/bj prefix; HK and Tencent's US use uppercase; the rest
     * lowercase. Returns null if [quoteProvider] doesn't support [marketType].
     */
    private fun providerSymbol(
        quoteProvider: StockerQuoteProvider,
        marketType: StockerMarketType,
        code: String,
    ): String? {
        val prefix = quoteProvider.providerPrefixMap[marketType] ?: return null
        return when {
            marketType == StockerMarketType.AShare -> prefixedAShareCode(code)
            marketType == StockerMarketType.HKStocks -> "$prefix${code.uppercase()}"
            marketType == StockerMarketType.Futures -> "$prefix${code.uppercase()}"
            quoteProvider == StockerQuoteProvider.TENCENT && marketType == StockerMarketType.USStocks ->
                "$prefix${code.uppercase()}"
            else -> "$prefix${code.lowercase()}"
        }
    }

    fun get(
        marketType: StockerMarketType, quoteProvider: StockerQuoteProvider, codes: List<String>
    ): Result<List<StockerQuote>> {
        if (codes.isEmpty()) {
            return Result.success(emptyList())
        }

        if (quoteProvider.providerPrefixMap[marketType] == null) {
            log.warn("Provider ${quoteProvider.title} does not support market type $marketType")
            return Result.success(emptyList())
        }

        val codesParam = codes.joinToString(",") { code ->
            providerSymbol(quoteProvider, marketType, code) ?: ""
        }

        val url = "${quoteProvider.host}${codesParam}"
        val httpGet = HttpGet(url)
        if (quoteProvider == StockerQuoteProvider.SINA) {
            httpGet.setHeader("Referer", "https://finance.sina.com.cn")
        }
        return try {
            httpClientPool.client().execute(httpGet).use { response ->
                val responseText = EntityUtils.toString(response.entity, "UTF-8")
                Result.success(StockerQuoteParser.parseQuoteResponse(quoteProvider, marketType, responseText))
            }
        } catch (e: Exception) {
            log.warn(e)
            Result.failure(e)
        }
    }

    /**
     * Fetch intraday minute-by-minute prices from Tencent finance API.
     * Returns a map of code -> StockerIntradayData.
     */
    fun getIntradayData(
        marketType: StockerMarketType, codes: List<String>
    ): Map<String, StockerIntradayData> {
        if (codes.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, StockerIntradayData>()
        for (code in codes) {
            try {
                val prefixedCode = when (marketType) {
                    StockerMarketType.AShare -> prefixedAShareCode(code)
                    StockerMarketType.HKStocks -> "hk$code"
                    StockerMarketType.USStocks -> "us${code.uppercase()}"
                    StockerMarketType.Crypto -> continue // Crypto not supported for intraday
                    StockerMarketType.Futures -> continue // Futures intraday endpoint differs; out of MVP scope
                }
                val rawCode = if (prefixedCode.startsWith("sh") || prefixedCode.startsWith("sz") || prefixedCode.startsWith("bj")) {
                    prefixedCode.substring(2)
                } else {
                    code
                }
                val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?_var=min_data_${rawCode}&code=$prefixedCode"
                val httpGet = HttpGet(url)
                httpClientPool.client().execute(httpGet).use { response ->
                    val responseText = EntityUtils.toString(response.entity, "UTF-8")
                    // Use uppercase rawCode as key to match table display (parser strips prefix)
                    val mapKey = rawCode.uppercase()
                    parseIntradayResponse(mapKey, responseText)?.let { result[mapKey] = it }
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch intraday data for $code: ${e.message}")
            }
        }
        return result
    }

    private fun parseIntradayResponse(code: String, responseText: String): StockerIntradayData? {
        // Response format: min_data_CODE={"code":0,"msg":"","data":{"shCODE":{"data":{"data":["HHMM price volume amount",...]}}}}
        val jsonStart = responseText.indexOf('{')
        if (jsonStart < 0) return null

        val json = responseText.substring(jsonStart)
        // Extract "data":["...","..."] array - simple parsing without JSON library
        val dataArrayStart = json.indexOf("\"data\":[\"")
        if (dataArrayStart < 0) return null

        val arrayStart = json.indexOf('[', dataArrayStart)
        val arrayEnd = json.indexOf(']', arrayStart)
        if (arrayStart < 0 || arrayEnd < 0) return null

        val arrayContent = json.substring(arrayStart + 1, arrayEnd)
        val prices = mutableListOf<Double>()
        // Each entry: "HHMM price volume amount"
        val entries = arrayContent.split("\",\"")
        for (entry in entries) {
            val cleaned = entry.trim('"')
            val parts = cleaned.split(" ")
            if (parts.size >= 2) {
                parts[1].toDoubleOrNull()?.let { prices.add(it) }
            }
        }

        if (prices.isEmpty()) return null

        // Extract yesterday's close from qt field: "qt":{"v_ff_shCODE":[],"shCODE":["1","name","code","...","close",...]}
        var close = prices.first()
        val qtStart = json.indexOf("\"qt\":")
        if (qtStart >= 0) {
            // Find the stock's qt array by looking for the code in a key followed by ":["
            // The key format varies: "sh600522", "hk00700", "usAAPL", etc.
            val qtKeyPattern = "$code\":["
            val stockQtPos = json.indexOf(qtKeyPattern, qtStart)
            if (stockQtPos >= 0) {
                val qtArrayStart = stockQtPos + qtKeyPattern.length - 1  // position of '['
                val qtArrayEnd = json.indexOf("]", qtArrayStart)
                if (qtArrayEnd >= 0) {
                    val qtContent = json.substring(qtArrayStart + 1, qtArrayEnd)
                    val qtParts = qtContent.split(",")
                    // Index 4 is yesterday's close in qt array
                    if (qtParts.size > 4) {
                        qtParts[4].trim('"').toDoubleOrNull()?.let { close = it }
                    }
                }
            }
        }

        return StockerIntradayData(code, prices, close)
    }

    fun validateCode(
        marketType: StockerMarketType, quoteProvider: StockerQuoteProvider, code: String
    ): Boolean {
        return try {
            val symbol = providerSymbol(quoteProvider, marketType, code)
            if (symbol == null) {
                log.warn("Provider ${quoteProvider.title} does not support market type $marketType")
                return false
            }
            val httpGet = HttpGet("${quoteProvider.host}$symbol")
            if (quoteProvider == StockerQuoteProvider.SINA) {
                httpGet.setHeader("Referer", "https://finance.sina.com.cn")
            }
            httpClientPool.client().execute(httpGet).use { response ->
                val responseText = EntityUtils.toString(response.entity, "UTF-8")
                when (quoteProvider) {
                    StockerQuoteProvider.SINA -> {
                        val firstLine = responseText.split("\n")[0]
                        val start = firstLine.indexOfFirst { it == '"' } + 1
                        val end = firstLine.indexOfLast { it == '"' }
                        start != end && firstLine.subSequence(start, end).contains(",")
                    }
                    StockerQuoteProvider.TENCENT -> !responseText.startsWith("v_pv_none_match")
                }
            }
        } catch (e: Exception) {
            log.warn(e)
            false
        }
    }
}
