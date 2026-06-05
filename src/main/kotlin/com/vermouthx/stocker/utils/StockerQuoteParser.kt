package com.vermouthx.stocker.utils

import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

object StockerQuoteParser {

    private fun Double.twoDigits(): Double {
        return (this * 100.0).roundToInt() / 100.0
    }

    private fun formatFuturesTimestamp(date: String, hhmmss: String): String {
        // date is "2026-06-05", hhmmss is "150418" (6 digits) or "90418" (5, missing leading zero).
        val padded = hhmmss.padStart(6, '0')
        val hh = padded.substring(0, 2)
        val mm = padded.substring(2, 4)
        val ss = padded.substring(4, 6)
        return "$date $hh:$mm:$ss"
    }

    fun parseQuoteResponse(
        provider: StockerQuoteProvider, marketType: StockerMarketType, responseText: String
    ): List<StockerQuote> {
        return when (provider) {
            StockerQuoteProvider.SINA -> parseSinaQuoteResponse(marketType, responseText)
            StockerQuoteProvider.TENCENT -> parseTencentQuoteResponse(marketType, responseText)
        }
    }

    private fun parseSinaQuoteResponse(marketType: StockerMarketType, responseText: String): List<StockerQuote> {
        val regex = Regex("var hq_str_(\\w+?)=\"(.*?)\";")
        return responseText.split("\n").asSequence()
            .filter { text -> text.isNotEmpty() }
            .mapNotNull { text ->
                val matchResult = regex.find(text) ?: return@mapNotNull null
                val (_, code, quote) = matchResult.groupValues
                if (quote.isBlank()) return@mapNotNull null // Skip empty responses (invalid/delisted codes)
                "${code},${quote}"
            }
            .map { text -> text.split(",") }
            .mapNotNull { textArray ->
                try {
                    parseSinaQuoteFields(marketType, textArray)
                } catch (e: Exception) {
                    null // Skip individual codes that fail to parse
                }
            }.toList()
    }

    private fun parseSinaQuoteFields(marketType: StockerMarketType, textArray: List<String>): StockerQuote? {
        if (textArray.size < 10) return null
        return when (marketType) {
                StockerMarketType.AShare -> {
                    // Keep the sh/sz/bj exchange prefix in the canonical row code so
                    // SH000001 (上证指数) and SZ000001 (平安银行) don't collapse into the
                    // same row identity. Display strips the prefix in CodeCellRenderer.
                    val code = textArray[0].uppercase()
                    val name = textArray[1]
                    val opening = textArray[2].toDouble()
                    val close = textArray[3].toDouble()
                    val current = textArray[4].toDouble()
                    val high = textArray[5].toDouble()
                    val low = textArray[6].toDouble()
                    val change = (current - close).twoDigits()
                    val percentage = ((current - close) / close * 100).twoDigits()
                    val updateAt = textArray[31] + " " + textArray[32]
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }

                StockerMarketType.HKStocks -> {
                    val code = textArray[0].substring(2).uppercase()
                    val name = textArray[2]
                    val opening = textArray[3].toDouble()
                    val close = textArray[4].toDouble()
                    val high = textArray[5].toDouble()
                    val low = textArray[6].toDouble()
                    val current = textArray[7].toDouble()
                    val change = (current - close).twoDigits()
                    val percentage = textArray[9].toDouble().twoDigits()
                    val sourceFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm")
                    val targetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    val datetime = LocalDateTime.parse(textArray[18] + " " + textArray[19], sourceFormatter)
                    val updateAt = targetFormatter.format(datetime)
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }

                StockerMarketType.USStocks -> {
                    val code = textArray[0].substring(3).uppercase()
                    val name = textArray[1]
                    val current = textArray[2].toDouble()
                    val updateAt = textArray[4]
                    val opening = textArray[6].toDouble()
                    val high = textArray[7].toDouble()
                    val low = textArray[8].toDouble()
                    val close = textArray[27].toDouble()
                    val change = (current - close).twoDigits()
                    val percentage = textArray[3].toDouble().twoDigits()
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }

                StockerMarketType.Crypto -> {
                    val code = textArray[0].substring(4).uppercase()
                    val name = textArray[10]
                    val current = textArray[9].toDouble()
                    val low = textArray[8].toDouble()
                    val high = textArray[7].toDouble()
                    val opening = textArray[6].toDouble()
                    val change = (current - opening).twoDigits()
                    val percentage = ((current - opening) / opening * 100).twoDigits()
                    val updateAt = "${textArray[12]} ${textArray[1]}"
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = current,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }

                StockerMarketType.Futures -> {
                    // Futures rows carry more columns than stocks (date at raw [17] / textArray [18]),
                    // so the outer `textArray.size < 10` guard isn't enough. Drop truncated responses
                    // rather than letting an IndexOutOfBoundsException be swallowed by the outer try.
                    if (textArray.size < 19) return null
                    // Sina nf_ contracts: textArray[0] is "nf_LH0" — strip the prefix so the
                    // canonical row key is "LH0" (what the user typed and stored).
                    // textArray index = rawIndex + 1 because parseSinaQuoteResponse prepends
                    // "${code}," to the splittable string (see line 33).
                    val code = textArray[0].removePrefix("nf_").uppercase()
                    val name = textArray[1]                        // raw [0]
                    val time = textArray[2]                        // raw [1] HHMMSS
                    val opening = textArray[3].toDouble()          // raw [2]
                    val high = textArray[4].toDouble()             // raw [3]
                    val low = textArray[5].toDouble()              // raw [4]
                    // Chinese futures convention: change is measured against 昨结算 (prev settle,
                    // raw [6]), not 昨收 (prev close, raw [10]).
                    val close = textArray[7].toDouble()            // raw [6] = 昨结算
                    val current = textArray[9].toDouble()          // raw [8] = 最新价
                    val change = (current - close).twoDigits()
                    val percentage = if (close != 0.0) ((current - close) / close * 100).twoDigits() else 0.0
                    val date = textArray[18]                       // raw [17] YYYY-MM-DD
                    val updateAt = formatFuturesTimestamp(date, time)
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }
            }
    }

    private fun parseTencentQuoteResponse(marketType: StockerMarketType, responseText: String): List<StockerQuote> {
        return responseText.split("\n").asSequence()
            .filter { text -> text.isNotEmpty() && !text.startsWith("v_pv_none_match") }
            .mapNotNull { text ->
                try {
                    val code = when (marketType) {
                        // Keep sh/sz/bj prefix; see comment in parseSinaQuoteFields.
                        StockerMarketType.AShare ->
                            text.subSequence(2, text.indexOfFirst { c -> c == '=' }).toString()
                        StockerMarketType.HKStocks, StockerMarketType.USStocks -> text.subSequence(4,
                            text.indexOfFirst { c -> c == '=' })
                        StockerMarketType.Crypto -> ""
                        StockerMarketType.Futures -> return@mapNotNull null
                    }
                    val content = text.subSequence(text.indexOfFirst { c -> c == '"' } + 1, text.indexOfLast { c -> c == '"' })
                    if (content.isBlank()) return@mapNotNull null
                    val combined = "$code~$content"
                    val textArray = combined.split("~")
                    if (textArray.size < 36) return@mapNotNull null
                    val parsedCode = textArray[0].uppercase()
                    val name = textArray[2]
                    val opening = textArray[6].toDouble()
                    val close = textArray[5].toDouble()
                    val current = textArray[4].toDouble()
                    val high = textArray[34].toDouble()
                    val low = textArray[35].toDouble()
                    val change = (current - close).twoDigits()
                    val percentage = textArray[33].toDouble().twoDigits()
                    val updateAt = when (marketType) {
                        StockerMarketType.AShare -> {
                            val sourceFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                            val targetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            val datetime = LocalDateTime.parse(textArray[31], sourceFormatter)
                            targetFormatter.format(datetime)
                        }
                        StockerMarketType.HKStocks -> {
                            val sourceFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")
                            val targetFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                            val datetime = LocalDateTime.parse(textArray[31], sourceFormatter)
                            targetFormatter.format(datetime)
                        }
                        StockerMarketType.USStocks -> textArray[31]
                        StockerMarketType.Crypto -> ""
                        // Unreachable: the code-extraction `when` above returns @mapNotNull null
                        // for Futures before reaching here. Kept for Kotlin exhaustiveness.
                        StockerMarketType.Futures -> ""
                    }
                    StockerQuote(
                        code = parsedCode,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                } catch (e: Exception) {
                    null // Skip individual codes that fail to parse
                }
            }.toList()
    }
}
