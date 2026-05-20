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
                    val rawCode = textArray[0]
                    // Strip sh/sz prefix added for API request
                    val code = if (rawCode.length > 2 && (rawCode.startsWith("sh") || rawCode.startsWith("sz") || rawCode.startsWith("bj"))) {
                        rawCode.substring(2).uppercase()
                    } else {
                        rawCode.uppercase()
                    }
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
            }
    }

    private fun parseTencentQuoteResponse(marketType: StockerMarketType, responseText: String): List<StockerQuote> {
        return responseText.split("\n").asSequence()
            .filter { text -> text.isNotEmpty() && !text.startsWith("v_pv_none_match") }
            .mapNotNull { text ->
                try {
                    val code = when (marketType) {
                        StockerMarketType.AShare -> {
                            val raw = text.subSequence(2, text.indexOfFirst { c -> c == '=' }).toString()
                            if (raw.startsWith("sh") || raw.startsWith("sz") || raw.startsWith("bj")) raw.substring(2) else raw
                        }
                        StockerMarketType.HKStocks, StockerMarketType.USStocks -> text.subSequence(4,
                            text.indexOfFirst { c -> c == '=' })
                        StockerMarketType.Crypto -> ""
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
