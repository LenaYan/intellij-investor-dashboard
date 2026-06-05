package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.views.StockerTableView

/**
 * Listener for the Favorites ("自选") tab. Subscribes to STOCK_ALL_QUOTE_UPDATE_TOPIC (which
 * carries the union of favorites and watchlist quotes) and filters down to quotes whose code
 * is present in the unified `favoritesList`.
 */
class FavoritesQuoteUpdateListener(tableView: StockerTableView) : StockerQuoteUpdateNotifier {

    private val delegate = StockerQuoteUpdateListener(tableView)

    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
        if (quotes.isEmpty()) return
        val setting = StockerSetting.instance
        val filtered = quotes.filter { setting.containsCode(it.code) }
        if (filtered.isEmpty()) return
        delegate.syncQuotes(filtered, filtered.size)
    }

    override fun syncIndices(indices: List<StockerQuote>) = delegate.syncIndices(indices)
}
