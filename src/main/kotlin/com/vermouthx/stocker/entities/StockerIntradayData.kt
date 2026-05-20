package com.vermouthx.stocker.entities

/**
 * Holds intraday minute-by-minute price data for sparkline rendering.
 */
data class StockerIntradayData(
    val code: String,
    val prices: List<Double>,
    val close: Double,
    val totalMinutes: Int = 242 // A-share default: 240 trading minutes + 1 open + 1 close
)
