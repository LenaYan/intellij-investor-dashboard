package com.vermouthx.stocker.finance

import kotlin.math.abs

/**
 * Computes the DISTANCE column cell for a given symbol + live price.
 *
 * Source priority:
 *   1. entry-timing.md recommendation (today's A+/A/B graded buy plan), if present
 *   2. watchlist.json target_zone / invalidation, as fallback
 *   3. otherwise null (cell stays empty)
 *
 * Levels — encoded as a single-letter prefix consumed by the renderer:
 *   A (ALERT)  current price has crossed below the invalidation price → red background
 *   W (WARN)   current price is inside the trigger ±1.5% window, or within +1.5% above the
 *              invalidation price → amber background
 *   I (INFO)   current price within ±5% of trigger but not yet hit → no background, plain text
 *   N (NONE)   passive distance display (>5% away) → muted gray
 *
 * Cell text format: `<arrow> ¥<anchor> <signed pct>%` plus a short label (触发 / 警戒 / 破).
 * Tooltip carries the full explanation including action ("立即清仓 / 加仓 …") so the table
 * stays compact while still being informative on hover.
 *
 * IMPORTANT: this class replaces what FinanceSignalDetector used to do via balloon popups
 * (TRIGGER_HIT / INVALIDATION_HIT / ENTRY_TRIGGER / ENTRY_INVALIDATION). The four kinds
 * still exist in FinanceNotifier.Kind for backward compat but are no longer fired.
 */
internal object FinanceDistanceAnnotator {

    enum class Level { NONE, INFO, WARN, ALERT }

    data class Cell(val level: Level, val text: String, val tooltip: String?)

    /** Cell value encoding written into the table model (consumed by the renderer). */
    fun encode(cell: Cell?): String? {
        if (cell == null) return null
        val l = when (cell.level) {
            Level.NONE -> "N"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ALERT -> "A"
        }
        return "$l|${cell.text}|${cell.tooltip ?: ""}"
    }

    /**
     * Compute the cell for [code] given current live [currentPrice]. Returns null when the
     * symbol has no watchlist nor entry-timing data — the column will stay empty for that row.
     */
    fun annotate(code: String, currentPrice: Double): Cell? {
        if (currentPrice <= 0) return null
        val bridge = FinanceBridgeService.instance
        val snap = bridge.snapshot()
        val key = FinanceSymbol.normalize(code)
        val rec = snap.entryTimingBySymbol[key]
        val watch = snap.watchlistBySymbol[key]
        if (rec == null && watch == null) return null

        // Prefer entry-timing prices (today's agent recommendation) over passive watchlist.
        // entry-timing 不买/C 等级仍然显示，但作为弱信号 — 用户可能想知道当前价位
        val source: String
        val anchorTrigger: Double?
        val anchorInv: Double?
        val gradeBadge: String?
        val actionHint: String?
        if (rec != null) {
            source = "entry-timing"
            anchorTrigger = rec.triggerPrice?.takeIf { it > 0 }
            anchorInv = rec.invalidationPrice?.takeIf { it > 0 }
            gradeBadge = rec.grade?.let { g ->
                val type = rec.entryType?.let { " · $it" } ?: ""
                "[$g$type] "
            }
            actionHint = buildString {
                if (rec.firstPositionPct != null) append("首仓 ${rec.firstPositionPct}%")
                if (rec.addSchedule != null) {
                    if (isNotEmpty()) append(" · ")
                    append("加仓 ${rec.addSchedule}")
                }
            }.takeIf { it.isNotEmpty() }
        } else {
            source = "watchlist"
            anchorTrigger = watch?.targetZoneLow?.takeIf { it > 0 }
            anchorInv = watch?.invalidationPrice?.takeIf { it > 0 }
            gradeBadge = null
            actionHint = watch?.trigger?.takeIf { it.isNotBlank() }
        }

        if (anchorTrigger == null && anchorInv == null) return null

        // 1) Invalidation breached → ALERT (highest priority, override everything)
        if (anchorInv != null && currentPrice < anchorInv) {
            val pct = (currentPrice - anchorInv) / anchorInv * 100.0
            val tip = buildString {
                append("已跌破失效价 ¥${"%.2f".format(anchorInv)}")
                if (actionHint != null) append("\n建议: $actionHint")
                append("\n来源: $source")
            }
            val text = "${gradeBadge.orEmpty()}破 ¥${"%.2f".format(anchorInv)} ${"%+.1f%%".format(pct)}"
            return Cell(Level.ALERT, text, tip)
        }

        // 2) Inside trigger ±1.5% window → WARN
        if (anchorTrigger != null) {
            val pctToTrig = (currentPrice - anchorTrigger) / anchorTrigger * 100.0
            if (abs(pctToTrig) <= 1.5) {
                val tip = buildString {
                    append("进入触发区 ¥${"%.2f".format(anchorTrigger)} ±1.5%")
                    if (actionHint != null) append("\n建议: $actionHint")
                    append("\n来源: $source")
                }
                val text = "${gradeBadge.orEmpty()}● ¥${"%.2f".format(anchorTrigger)} ${"%+.1f%%".format(pctToTrig)}"
                return Cell(Level.WARN, text, tip)
            }
        }

        // 3) Above invalidation but within +1.5% danger zone → WARN
        if (anchorInv != null && currentPrice >= anchorInv && currentPrice <= anchorInv * 1.015) {
            val pct = (currentPrice - anchorInv) / anchorInv * 100.0
            val tip = buildString {
                append("接近失效价 ¥${"%.2f".format(anchorInv)} (+${"%.1f".format(pct)}%)")
                append("\n再跌破即触发减仓 / 清仓建议")
                append("\n来源: $source")
            }
            val text = "${gradeBadge.orEmpty()}↘ ¥${"%.2f".format(anchorInv)} 警戒 ${"%+.1f%%".format(pct)}"
            return Cell(Level.WARN, text, tip)
        }

        // 4) Within ±5% of trigger → INFO
        if (anchorTrigger != null) {
            val pctToTrig = (currentPrice - anchorTrigger) / anchorTrigger * 100.0
            if (abs(pctToTrig) <= 5.0) {
                val arrow = if (pctToTrig < 0) "↘" else "↗"
                val tip = buildString {
                    append("距 trigger ¥${"%.2f".format(anchorTrigger)} ${"%+.1f%%".format(pctToTrig)}")
                    if (actionHint != null) append("\n计划: $actionHint")
                    append("\n来源: $source")
                }
                val text = "${gradeBadge.orEmpty()}$arrow ¥${"%.2f".format(anchorTrigger)} ${"%+.1f%%".format(pctToTrig)}"
                return Cell(Level.INFO, text, tip)
            }
        }

        // 5) Otherwise: passive distance display
        val anchor = anchorTrigger ?: anchorInv ?: return null
        val pct = (currentPrice - anchor) / anchor * 100.0
        val arrow = if (pct < 0) "↘" else "↗"
        val tip = "距 ${if (anchor == anchorTrigger) "trigger" else "invalidation"} " +
            "¥${"%.2f".format(anchor)} ${"%+.1f%%".format(pct)}\n来源: $source"
        return Cell(Level.NONE, "${gradeBadge.orEmpty()}$arrow ${"%+.1f%%".format(pct)}", tip)
    }
}
