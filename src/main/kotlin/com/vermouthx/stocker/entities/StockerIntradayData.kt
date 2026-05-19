package com.vermouthx.stocker.entities

/**
 * Holds intraday minute-by-minute price data for sparkline rendering.
 */
data class StockerIntradayData(
    val code: String,
    val prices: List<Double>,
    val close: Double
)
