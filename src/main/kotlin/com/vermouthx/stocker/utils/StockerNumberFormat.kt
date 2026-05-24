package com.vermouthx.stocker.utils

/**
 * Shared formatters for the holdings columns. Previously duplicated across
 * StockerTableView, StockerQuoteUpdateListener and StockerManagementDialog.
 */
object StockerNumberFormat {

    const val EMPTY: String = "-"

    @JvmStatic
    fun formatPrice(price: Double?): String =
        if (price != null) String.format("%.3f", price) else EMPTY

    @JvmStatic
    fun formatHoldings(holdings: Int?): Any =
        holdings ?: EMPTY

    @JvmStatic
    fun formatNetProfit(currentPrice: Double?, costPrice: Double?, holdings: Int?): Any {
        if (currentPrice == null || costPrice == null || holdings == null) return EMPTY
        return String.format("%.3f", (currentPrice - costPrice) * holdings)
    }
}
