package com.vermouthx.stocker.listeners;

import com.vermouthx.stocker.entities.StockerQuote;
import com.vermouthx.stocker.finance.FinanceBridgeService;
import com.vermouthx.stocker.finance.FinanceSymbol;
import com.vermouthx.stocker.views.StockerTableView;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Listener for the Watchlist tab. Subscribes to STOCK_ALL_QUOTE_UPDATE_TOPIC (which carries
 * quotes for every market) and filters down to symbols present in
 * {@code ~/Claude/finance/watchlist.json}. Filtered quotes are forwarded to a normal
 * {@link StockerQuoteUpdateListener} which then drives the table model exactly like any
 * other market tab.
 *
 * Why a wrapper rather than a new message-bus topic: the consolidated fetch in
 * {@code StockerApp} already issues each quote on the ALL topic once. Adding a watchlist
 * topic would just duplicate that work. Keeping the wiring on the ALL topic + filter is
 * the minimum new code.
 *
 * Indices are intentionally NOT forwarded — the watchlist spans markets so a single
 * index dropdown wouldn't make sense. The combobox stays empty.
 */
public class WatchlistQuoteUpdateListener implements StockerQuoteUpdateNotifier {

    private final StockerQuoteUpdateListener delegate;

    public WatchlistQuoteUpdateListener(StockerTableView tableView) {
        this.delegate = new StockerQuoteUpdateListener(tableView);
    }

    @Override
    public void syncQuotes(List<StockerQuote> quotes, int size) {
        if (quotes == null || quotes.isEmpty()) return;
        Set<String> watchlistKeys = FinanceBridgeService.getInstance()
            .snapshot()
            .getWatchlistBySymbol()
            .keySet();
        if (watchlistKeys.isEmpty()) return;

        List<StockerQuote> filtered = new ArrayList<>(watchlistKeys.size());
        for (StockerQuote q : quotes) {
            if (q == null) continue;
            String key = FinanceSymbol.normalize(q.getCode());
            if (watchlistKeys.contains(key)) {
                filtered.add(q);
            }
        }
        if (filtered.isEmpty()) return;
        // size = filtered.size() so the delegate's addRow guard allows every filtered
        // quote into the table (the guard uses quotes.size <= size to cap new rows
        // against the configured list size — here the "configured list" is precisely
        // what we just filtered).
        delegate.syncQuotes(filtered, filtered.size());
    }

    @Override
    public void syncIndices(List<StockerQuote> indices) {
        // Watchlist tab does not show a single-market index — skip.
    }
}
