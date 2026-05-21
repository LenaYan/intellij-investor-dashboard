package com.vermouthx.stocker.finance

import com.vermouthx.stocker.entities.StockerQuote
import kotlin.math.abs

/**
 * Pure-logic anomaly detector — given the latest quotes and the current finance state,
 * decide which signals should fire. Stateful only via [FinanceNotifier]'s throttle.
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
        val notifyTriggers: Boolean,
        val notifyEntryTiming: Boolean,
    )

    fun onQuotes(quotes: List<StockerQuote>) {
        val c = config()
        if (!c.notifyAnomaly && !c.notifyTriggers && !c.notifyEntryTiming) return
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

            val pct = q.percentage
            val cur = q.current

            if (c.notifyAnomaly) {
                detectAnomaly(code, q, pct, cur, c)
            }
            if (c.notifyTriggers) {
                detectTriggers(code, q, cur, key)
            }
            if (c.notifyEntryTiming) {
                detectEntryTiming(code, q, cur, key)
            }
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

    private fun detectTriggers(code: String, q: StockerQuote, cur: Double, normalizedKey: String) {
        val entry = state.get().watchlistBySymbol[normalizedKey] ?: return
        val low = entry.targetZoneLow
        // 1. Target-zone-low: pull-back into [-2%, +5%] of the low edge fires a TRIGGER_HIT
        if (low != null && low > 0) {
            val lowerBound = low * 0.98
            val upperBound = low * 1.05
            if (cur in lowerBound..upperBound) {
                val detail = "watchlist 触发买点: 进入 target_zone 下沿 ¥$low"
                notifier.fire(code, q.name, FinanceNotifier.Kind.TRIGGER_HIT, cur, q.percentage, detail = detail)
            }
        }
        // 2. Invalidation price extracted heuristically from text (first number in invalidation field)
        val inv = entry.invalidationPrice
        if (inv != null && inv > 0 && cur < inv) {
            val detail = "watchlist 触发证伪: 跌破 ¥$inv (来自 invalidation 文本)"
            notifier.fire(code, q.name, FinanceNotifier.Kind.INVALIDATION_HIT, cur, q.percentage, detail = detail)
        }
    }

    /**
     * Cross-reference live price against today's entry-timing.md recommendations.
     *
     * Trigger logic:
     *   - `triggerPrice` ∈ ±1.5% window → ENTRY_TRIGGER (recommended buy zone)
     *   - `invalidationPrice` crossed downward → ENTRY_INVALIDATION (recommended stop)
     *
     * Throttle is per-symbol-per-kind-per-day so spam during oscillation is bounded.
     */
    private fun detectEntryTiming(code: String, q: StockerQuote, cur: Double, normalizedKey: String) {
        val rec = state.get().entryTimingBySymbol[normalizedKey] ?: return
        // Only react for A/A+ recommendations or B grades; skip "不买" entries.
        val grade = rec.grade?.trim()
        if (grade == "不买" || grade == "C") return

        val trig = rec.triggerPrice
        if (trig != null && trig > 0) {
            val lower = trig * 0.985
            val upper = trig * 1.015
            if (cur in lower..upper) {
                val triggerText = rec.triggers.firstOrNull()?.let { " — $it" }.orEmpty()
                val detail = buildString {
                    append("【${grade ?: "?"} · ${rec.entryType ?: "?"}】")
                    append(" 接近触发价 ¥${"%.2f".format(trig)}")
                    if (rec.firstPositionPct != null) {
                        append("\n建议首仓 ${rec.firstPositionPct}% · 加仓 ${rec.addSchedule ?: "—"}")
                    }
                    if (!rec.alignedThread.isNullOrBlank()) {
                        append("\n主线: ${rec.alignedThread} (${rec.threadPhase ?: "?"})")
                    }
                    append(triggerText)
                }
                notifier.fire(code, q.name ?: rec.name, FinanceNotifier.Kind.ENTRY_TRIGGER, cur, q.percentage, detail = detail)
            }
        }

        val inv = rec.invalidationPrice
        if (inv != null && inv > 0 && cur < inv) {
            val invText = rec.invalidations.firstOrNull()?.let { " — $it" }.orEmpty()
            val detail = buildString {
                append("【${grade ?: "?"} · ${rec.entryType ?: "?"}】跌破失效价 ¥${"%.2f".format(inv)}")
                append(invText)
                append("\nentry-timing 建议: 立即终止建仓 / 已有持仓减仓")
            }
            notifier.fire(code, q.name ?: rec.name, FinanceNotifier.Kind.ENTRY_INVALIDATION, cur, q.percentage, detail = detail)
        }
    }
}
