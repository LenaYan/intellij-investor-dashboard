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
     * If the code already has sh/sz prefix, return as-is (lowercased).
     * Otherwise, determine prefix: Shanghai (sh) for codes starting with 6, 9, 5;
     * Shenzhen (sz) for all others.
     */
    fun prefixedAShareCode(code: String): String {
        val lower = code.lowercase()
        if (lower.startsWith("sh") || lower.startsWith("sz")) {
            return lower
        }
        val prefix = when (lower[0]) {
            '6', '9', '5' -> "sh"
            else -> "sz"
        }
        return "$prefix$lower"
    }

    fun closeConnections() {
        httpClientPool.close()
    }

    fun get(
        marketType: StockerMarketType, quoteProvider: StockerQuoteProvider, codes: List<String>
    ): List<StockerQuote> {
        if (codes.isEmpty()) {
            return emptyList()
        }
        
        // Validate provider supports market type
        val prefix = quoteProvider.providerPrefixMap[marketType]
        if (prefix == null) {
            log.warn("Provider ${quoteProvider.title} does not support market type $marketType")
            return emptyList()
        }
        
        val codesParam = when (quoteProvider) {
            StockerQuoteProvider.SINA -> {
                if (marketType == StockerMarketType.HKStocks) {
                    codes.joinToString(",") { code ->
                        "$prefix${code.uppercase()}"
                    }
                } else if (marketType == StockerMarketType.AShare) {
                    // A-share codes need sh/sz prefix based on code number
                    codes.joinToString(",") { code ->
                        prefixedAShareCode(code)
                    }
                } else {
                    // USStocks, Crypto use lowercase with provider prefix
                    codes.joinToString(",") { code ->
                        "$prefix${code.lowercase()}"
                    }
                }
            }

            StockerQuoteProvider.TENCENT -> {
                if (marketType == StockerMarketType.HKStocks || marketType == StockerMarketType.USStocks) {
                    codes.joinToString(",") { code ->
                        "$prefix${code.uppercase()}"
                    }
                } else if (marketType == StockerMarketType.AShare) {
                    // A-share codes need sh/sz prefix based on code number
                    codes.joinToString(",") { code ->
                        prefixedAShareCode(code)
                    }
                } else {
                    codes.joinToString(",") { code ->
                        "$prefix${code.lowercase()}"
                    }
                }
            }
        }

        val url = "${quoteProvider.host}${codesParam}"
        val httpGet = HttpGet(url)
        if (quoteProvider == StockerQuoteProvider.SINA) {
            httpGet.setHeader("Referer", "https://finance.sina.com.cn") // Sina API requires this header
        }
        return try {
            httpClientPool.client().execute(httpGet).use { response ->
                val responseText = EntityUtils.toString(response.entity, "UTF-8")
                StockerQuoteParser.parseQuoteResponse(quoteProvider, marketType, responseText)
            }
        } catch (e: Exception) {
            log.warn(e)
            emptyList()
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
                }
                val rawCode = if (prefixedCode.startsWith("sh") || prefixedCode.startsWith("sz")) {
                    prefixedCode.substring(2)
                } else {
                    code
                }
                val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?_var=min_data_${rawCode}&code=$prefixedCode"
                val httpGet = HttpGet(url)
                httpClientPool.client().execute(httpGet).use { response ->
                    val responseText = EntityUtils.toString(response.entity, "UTF-8")
                    parseIntradayResponse(code, responseText)?.let { result[code] = it }
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

        // Extract yesterday's close from qt field: "qt":{"shCODE":["...","...","close",...]}
        var close = prices.first()
        val qtStart = json.indexOf("\"qt\":")
        if (qtStart >= 0) {
            val qtArrayStart = json.indexOf("[", qtStart)
            if (qtArrayStart >= 0) {
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
            // Validate provider supports market type
            val prefix = quoteProvider.providerPrefixMap[marketType]
            if (prefix == null) {
                log.warn("Provider ${quoteProvider.title} does not support market type $marketType")
                return false
            }
            
            when (quoteProvider) {
                StockerQuoteProvider.SINA -> {
                    val url = if (marketType == StockerMarketType.HKStocks) {
                        "${quoteProvider.host}$prefix${code.uppercase()}"
                    } else if (marketType == StockerMarketType.AShare) {
                        "${quoteProvider.host}${prefixedAShareCode(code)}"
                    } else {
                        "${quoteProvider.host}$prefix${code.lowercase()}"
                    }
                    val httpGet = HttpGet(url)
                    httpGet.setHeader("Referer", "https://finance.sina.com.cn") // Sina API requires this header
                    httpClientPool.client().execute(httpGet).use { response ->
                        val responseText = EntityUtils.toString(response.entity, "UTF-8")
                        val firstLine = responseText.split("\n")[0]
                        val start = firstLine.indexOfFirst { c -> c == '"' } + 1
                        val end = firstLine.indexOfLast { c -> c == '"' }
                        if (start == end) {
                            return false
                        }
                        firstLine.subSequence(start, end).contains(",")
                    }
                }

                StockerQuoteProvider.TENCENT -> {
                    val url = if (marketType == StockerMarketType.HKStocks || marketType == StockerMarketType.USStocks) {
                        "${quoteProvider.host}$prefix${code.uppercase()}"
                    } else if (marketType == StockerMarketType.AShare) {
                        "${quoteProvider.host}${prefixedAShareCode(code)}"
                    } else {
                        "${quoteProvider.host}$prefix${code.lowercase()}"
                    }
                    val httpGet = HttpGet(url)
                    httpClientPool.client().execute(httpGet).use { response ->
                        val responseText = EntityUtils.toString(response.entity, "UTF-8")
                        !responseText.startsWith("v_pv_none_match")
                    }
                }
            }
        } catch (e: Exception) {
            log.warn(e)
            false
        }
    }
}
