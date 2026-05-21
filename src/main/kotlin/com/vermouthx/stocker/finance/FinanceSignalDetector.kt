package com.vermouthx.stocker.finance

import com.vermouthx.stocker.entities.StockerQuote
import kotlin.math.abs

/**
 * Pure-logic anomaly detector — given the latest quotes and the current finance state,
 * decide which **anomaly** signals should fire as IDE notifications. Stateful only via
 * [FinanceNotifier]'s throttle.
 *
 * **As of v1.21.0**: watchlist trigger / invalidation hits and entry-timing buy-point hits
 * are no longer surfaced as balloon popups — they now appear inline in the DISTANCE table
 * column (see [FinanceDistanceAnnotator]). Only market anomaly signals (±5% / ±7% spikes,
 * A-share limit up / limit down) still produce notifications because those are genuine
 * intraday surprises that warrant an interrupt.
 *
 * The `notifyTriggers` / `notifyEntryTiming` config flags now control whether the DISTANCE
 * column applies WARN / ALERT background colors (read by the renderer); they no longer gate
 * popup firing here.
 *
 * No IntelliJ types here so this class is unit-testable.
 */
internal class FinanceSignalDetector(
    private val notifier: FinanceNotifier,
    private val state: FinanceState,
    private val config: () -> Config,
) {
    data class Config(
        val anomalyPct: Double,          // ±5%
        val strongAnomalyPct: Double,    // ±7%
        val notifyAnomaly: Boolean,
        val notifyTriggers: Boolean,     // retained for renderer gating (DISTANCE highlights)
        val notifyEntryTiming: Boolean,  // retained for renderer gating (DISTANCE highlights)
    )

    fun onQuotes(quotes: List<StockerQuote>) {
        val c = config()
        if (!c.notifyAnomaly) return     // anomaly popups are the only thing fired here now
        if (quotes.isEmpty()) return
        val snap = state.get()
        val watchlistKeys = snap.watchlistBySymbol.keys
        val portfolioKeys = snap.portfolioBySymbol.keys
        val entryKeys = snap.entryTimingBySymbol.keys

        for (q in quotes) {
            val code = q.code
            if (code.isBlank()) continue
            val key = FinanceSymbol.normalize(code)
            val tracked = key in watchlistKeys || key in portfolioKeys || key in entryKeys
            if (!tracked) continue

            detectAnomaly(code, q, q.percentage, q.current, c)
        }
    }

    private fun detectAnomaly(code: String, q: StockerQuote, pct: Double, cur: Double, c: Config) {
        val limit = if (FinanceSymbol.isAShareCode(code)) FinanceSymbol.limitPctOfAShare(code) else Double.MAX_VALUE
        val nearLimit = limit - 0.6   // within 0.6pp of the cap counts as limit-hit
        val absPct = abs(pct)

        when {
            FinanceSymbol.isAShareCode(code) && pct >= nearLimit -> {
                notifier.fire(code, q.name, FinanceNotifier.Kind.LIMIT_UP, cur, pct, detail = null)
                return
            }
            FinanceSymbol.isAShareCode(code) && pct <= -nearLimit -> {
                notifier.fire(code, q.name, FinanceNotifier.Kind.LIMIT_DOWN, cur, pct, detail = null)
                return
            }
            absPct >= c.strongAnomalyPct -> {
                val kind = if (pct > 0) FinanceNotifier.Kind.STRONG_ANOMALY_UP
                else FinanceNotifier.Kind.STRONG_ANOMALY_DOWN
                notifier.fire(code, q.name, kind, cur, pct, detail = null)
            }
            absPct >= c.anomalyPct -> {
                val kind = if (pct > 0) FinanceNotifier.Kind.ANOMALY_UP
                else FinanceNotifier.Kind.ANOMALY_DOWN
                notifier.fire(code, q.name, kind, cur, pct, detail = null)
            }
        }
    }

}
