package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceSymbol
import com.vermouthx.stocker.views.StockerTableView

/**
 * Listener for the Watchlist tab. Subscribes to STOCK_ALL_QUOTE_UPDATE_TOPIC (which carries
 * quotes for every market) and filters down to symbols present in
 * `~/Claude/finance/watchlist.json`. Filtered quotes are forwarded to a normal
 * [StockerQuoteUpdateListener] which then drives the table model exactly like any
 * other market tab.
 *
 * Why a wrapper rather than a new message-bus topic: the consolidated fetch in
 * `StockerApp` already issues each quote on the ALL topic once. Adding a watchlist
 * topic would just duplicate that work. Keeping the wiring on the ALL topic + filter is
 * the minimum new code.
 *
 * Indices are intentionally NOT forwarded — the watchlist spans markets so a single
 * index dropdown wouldn't make sense. The combobox stays empty.
 */
class WatchlistQuoteUpdateListener(tableView: StockerTableView) : StockerQuoteUpdateNotifier {

    private val delegate = StockerQuoteUpdateListener(tableView)

    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
        if (quotes.isEmpty()) return
        val watchlistKeys = FinanceBridgeService.instance.snapshot().watchlistBySymbol.keys
        if (watchlistKeys.isEmpty()) return

        val filtered = quotes.filter { FinanceSymbol.normalize(it.code) in watchlistKeys }
        if (filtered.isEmpty()) return
        // size = filtered.size so the delegate's addRow guard allows every filtered
        // quote into the table (the guard uses quotes.size <= size to cap new rows
        // against the configured list size — here the "configured list" is precisely
        // what we just filtered).
        delegate.syncQuotes(filtered, filtered.size)
    }

    /** Watchlist tab does not show a single-market index — skip. */
    override fun syncIndices(indices: List<StockerQuote>) = Unit
}
