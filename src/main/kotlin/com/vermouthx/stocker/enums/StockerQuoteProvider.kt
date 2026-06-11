package com.vermouthx.stocker.enums

import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.api.StockerQuoteSource
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.utils.StockerQuoteParser

/**
 * Known quote sources. Remains an enum (settings serialize the constant name) but each
 * constant carries its full [StockerQuoteSource] behaviour, so callers never branch on
 * the provider type.
 */
enum class StockerQuoteProvider(
    val titleKey: String,
    override val host: String,
    override val suggestHost: String,
    override val providerPrefixMap: Map<StockerMarketType, String>,
) : StockerQuoteSource {

    /**
     * Sina API
     */
    SINA(
        titleKey = "provider.sina",
        host = "https://hq.sinajs.cn/list=",
        suggestHost = "https://suggest3.sinajs.cn/suggest/key=",
        providerPrefixMap = mapOf(
            StockerMarketType.AShare to "",
            StockerMarketType.HKStocks to "hk",
            StockerMarketType.USStocks to "gb_",
            StockerMarketType.Crypto to "btc_",
            StockerMarketType.Futures to "nf_"
        )
    ) {
        override fun extraHeaders(): Map<String, String> =
            mapOf("Referer" to "https://finance.sina.com.cn")

        override fun parseQuoteResponse(market: StockerMarketType, responseText: String): List<StockerQuote> =
            StockerQuoteParser.parseSinaQuoteResponse(market, responseText)

        override fun isValidCodeResponse(responseText: String): Boolean {
            val firstLine = responseText.split("\n")[0]
            val start = firstLine.indexOfFirst { it == '"' } + 1
            val end = firstLine.indexOfLast { it == '"' }
            return start != end && firstLine.subSequence(start, end).contains(",")
        }
    },

    /**
     * Tencent API
     */
    TENCENT(
        titleKey = "provider.tencent",
        host = "https://qt.gtimg.cn/q=",
        suggestHost = "https://smartbox.gtimg.cn/s3/?v=2&t=all&c=1&q=",
        providerPrefixMap = mapOf(
            StockerMarketType.AShare to "",
            StockerMarketType.HKStocks to "hk",
            StockerMarketType.USStocks to "us",
        )
    ) {
        override fun parseQuoteResponse(market: StockerMarketType, responseText: String): List<StockerQuote> =
            StockerQuoteParser.parseTencentQuoteResponse(market, responseText)

        override fun isValidCodeResponse(responseText: String): Boolean =
            !responseText.startsWith("v_pv_none_match")
    };

    val title: String
        get() = StockerBundle.message(titleKey)

    /** The other source to try within the same tick when this one fails a fetch. */
    fun fallback(): StockerQuoteProvider = when (this) {
        SINA -> TENCENT
        TENCENT -> SINA
    }

    companion object {
        fun fromTitle(title: String): StockerQuoteProvider {
            return when (title) {
                SINA.title -> SINA
                TENCENT.title -> TENCENT
                else -> SINA
            }
        }
    }

}
