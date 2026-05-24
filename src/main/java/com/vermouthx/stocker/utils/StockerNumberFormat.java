package com.vermouthx.stocker.utils;

/**
 * Shared formatters for the holdings columns. Previously duplicated across
 * StockerTableView, StockerQuoteUpdateListener and StockerManagementDialog.
 */
public final class StockerNumberFormat {

    public static final String EMPTY = "-";

    public static String formatPrice(Double price) {
        return price != null ? String.format("%.3f", price) : EMPTY;
    }

    public static Object formatHoldings(Integer holdings) {
        return holdings != null ? holdings : EMPTY;
    }

    public static Object formatNetProfit(Double currentPrice, Double costPrice, Integer holdings) {
        if (currentPrice == null || costPrice == null || holdings == null) {
            return EMPTY;
        }
        return String.format("%.3f", (currentPrice - costPrice) * holdings);
    }

    private StockerNumberFormat() {
    }
}
