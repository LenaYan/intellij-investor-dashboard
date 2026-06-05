package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.views.StockerTableView

/**
 * Listener for the Favorites ("自选") tab. Subscribes to STOCK_ALL_QUOTE_UPDATE_TOPIC (which
 * carries the union of favorites and watchlist quotes — folded together upstream by
 * [com.vermouthx.stocker.StockerApp] to avoid duplicate HTTP requests) and filters down to
 * quotes whose code is present in one of `setting.aShareList/hkStocksList/usStocksList/cryptoList`.
 *
 * Without this filter the favorites tab would also display watchlist-only entries, which the
 * user has no UI to delete (right-click → marketOf == null) and which collide on row identity
 * when SH/SZ same-numbered codes appear. The mirror to this class is
 * [WatchlistQuoteUpdateListener], which filters the same stream to watchlist-only.
 */
class FavoritesQuoteUpdateListener(tableView: StockerTableView) : StockerQuoteUpdateNotifier {

    private val delegate = StockerQuoteUpdateListener(tableView)

    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
        if (quotes.isEmpty()) return
        val setting = StockerSetting.instance
        val filtered = quotes.filter { setting.containsCode(it.code) }
        if (filtered.isEmpty()) return
        // size = filtered.size keeps the delegate's `quotes.size <= size` addRow guard
        // permissive for everything that passed the favorites filter.
        delegate.syncQuotes(filtered, filtered.size)
    }

    /** Indices flow through unchanged — they drive the favorites tab's index combo box. */
    override fun syncIndices(indices: List<StockerQuote>) = delegate.syncIndices(indices)
}
