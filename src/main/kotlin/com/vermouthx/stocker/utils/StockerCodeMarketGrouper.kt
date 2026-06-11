package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType

/**
 * Groups bare table codes into market buckets for the intraday fetch. Favorites are
 * authoritative; codes that only exist in the finance/ watchlist (and therefore have
 * no favorites entry) resolve through [watchlistByMarket] so their sparklines still
 * receive data. Codes neither source knows are dropped.
 */
object StockerCodeMarketGrouper {

    fun group(
        codes: Collection<String>,
        favoriteMarketOf: (String) -> StockerMarketType?,
        watchlistByMarket: Map<StockerMarketType, List<String>>,
    ): Map<StockerMarketType, Set<String>> {
        val watchlistMarketByCode = HashMap<String, StockerMarketType>()
        for ((market, marketCodes) in watchlistByMarket) {
            for (code in marketCodes) watchlistMarketByCode.putIfAbsent(code, market)
        }
        val grouped = HashMap<StockerMarketType, MutableSet<String>>()
        for (code in codes) {
            val market = favoriteMarketOf(code) ?: watchlistMarketByCode[code] ?: continue
            grouped.getOrPut(market) { HashSet() }.add(code)
        }
        return grouped
    }
}
