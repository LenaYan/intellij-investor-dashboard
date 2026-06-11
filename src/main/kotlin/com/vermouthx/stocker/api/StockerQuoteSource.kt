package com.vermouthx.stocker.api

import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketType

/**
 * Strategy surface for a quote data source. [com.vermouthx.stocker.enums.StockerQuoteProvider]
 * constants implement this, so per-provider behaviour (URL shape, headers, response parsing,
 * validation) lives with the provider instead of in `when(provider)` branches scattered
 * across the HTTP and parser utilities. Adding a source = adding one enum constant.
 */
interface StockerQuoteSource {

    /** Quote endpoint prefix; the comma-joined symbol list is appended directly. */
    val host: String

    /** Symbol-suggestion endpoint prefix. */
    val suggestHost: String

    /** Per-market symbol prefix; a market absent from this map is unsupported. */
    val providerPrefixMap: Map<StockerMarketType, String>

    fun supports(market: StockerMarketType): Boolean = providerPrefixMap.containsKey(market)

    /** Extra HTTP headers the endpoint requires (e.g. Sina's Referer check). */
    fun extraHeaders(): Map<String, String> = emptyMap()

    /** Parse a raw quote response body into quotes. Must never throw on malformed input. */
    fun parseQuoteResponse(market: StockerMarketType, responseText: String): List<StockerQuote>

    /** True when the response body indicates the queried symbol exists. */
    fun isValidCodeResponse(responseText: String): Boolean
}
