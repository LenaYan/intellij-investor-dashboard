package com.vermouthx.stocker.listeners

import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerTableColumn
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceDistanceAnnotator
import com.vermouthx.stocker.finance.FinanceEventCalendar
import com.vermouthx.stocker.finance.FinanceState
import com.vermouthx.stocker.finance.FinanceSymbol
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerNumberFormat
import com.vermouthx.stocker.utils.StockerTableModelUtil
import com.vermouthx.stocker.views.StockerTableView
import javax.swing.SwingUtilities
import javax.swing.table.DefaultTableModel

class StockerQuoteUpdateListener(private val myTableView: StockerTableView) : StockerQuoteUpdateNotifier {

    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
        // Quotes arrive on the refresh executor thread; Swing models must only be
        // mutated on the EDT, so the whole batch is marshalled over in one hop.
        SwingUtilities.invokeLater {
            applyQuotes(quotes, size)
        }
    }

    /** Model column indices resolved by name once per batch — never hardcoded positions. */
    private class QuoteColumns(model: DefaultTableModel) {
        val symbol     = StockerTableModelUtil.colOf(model, StockerTableColumn.SYMBOL)
        val name       = StockerTableModelUtil.colOf(model, StockerTableColumn.NAME)
        val current    = StockerTableModelUtil.colOf(model, StockerTableColumn.CURRENT)
        val opening    = StockerTableModelUtil.colOf(model, StockerTableColumn.OPENING)
        val close      = StockerTableModelUtil.colOf(model, StockerTableColumn.CLOSE)
        val low        = StockerTableModelUtil.colOf(model, StockerTableColumn.LOW)
        val high       = StockerTableModelUtil.colOf(model, StockerTableColumn.HIGH)
        val change     = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE)
        val percent    = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE_PERCENT)
        val costPrice  = StockerTableModelUtil.colOf(model, StockerTableColumn.COST_PRICE)
        val holdings   = StockerTableModelUtil.colOf(model, StockerTableColumn.HOLDINGS)
        val netProfit  = StockerTableModelUtil.colOf(model, StockerTableColumn.NET_PROFIT)
        val health     = StockerTableModelUtil.colOf(model, StockerTableColumn.HEALTH)
        val distance   = StockerTableModelUtil.colOf(model, StockerTableColumn.DISTANCE)
        val updateTime = StockerTableModelUtil.colOf(model, StockerTableColumn.UPDATE_TIME)
    }

    private fun applyQuotes(quotes: List<StockerQuote>, size: Int) {
        val model = myTableView.tableModel
        val setting = StockerSetting.instance
        val cols = QuoteColumns(model)

        // Hold the lock for the whole batch — previously each quote re-acquired it.
        synchronized(model) {
            // One-pass index instead of a per-quote linear scan (O(rows²) per tick).
            val rowByCode = StockerTableModelUtil.rowIndexByCode(model)
            for (quote in quotes) {
                val code = quote.code
                val displayName = prefixNameWithEvents(setting.getDisplayName(code, quote.name), code)
                val costPrice = setting.getCostPrice(code)
                val holdings = setting.getHoldings(code)

                val rowIndex = rowByCode[code] ?: -1
                if (rowIndex != -1) {
                    // setIfChanged emits one cell-updated event per actually changed cell
                    // (via DefaultTableModel.setValueAt); no row-level fire on top, so an
                    // unchanged row costs zero repaints per tick.
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.name, displayName)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.current, quote.current)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.opening, quote.opening)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.close, quote.close)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.low, quote.low)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.high, quote.high)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.change, quote.change)
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.percent, "${quote.percentage}%")
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.costPrice, StockerNumberFormat.formatPrice(costPrice))
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.holdings, StockerNumberFormat.formatHoldings(holdings))
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.netProfit,
                        StockerNumberFormat.formatNetProfit(quote.current, costPrice, holdings))
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.health, formatHealthBadge(code))
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.distance,
                        FinanceDistanceAnnotator.encode(FinanceDistanceAnnotator.annotate(code, quote.current)))
                    StockerTableModelUtil.setIfChanged(model, rowIndex, cols.updateTime, formatUpdateTime(quote.updateAt))
                } else if (quotes.size <= size) {
                    model.addRow(buildRow(quote, displayName, costPrice, holdings, model.columnCount, cols))
                    rowByCode[code] = model.rowCount - 1
                    myTableView.clearSortState()
                }
            }
        }
    }

    override fun syncIndices(indices: List<StockerQuote>) {
        // StockerTableView.syncIndices marshals to the EDT itself.
        myTableView.syncIndices(indices)
    }

    companion object {
        /**
         * Prefix the display name with calendar-event emojis (📊 earnings, 🔓 unlock) and
         * entry-timing grade (🟢A+/🟢A/🟡B/🔴C) when this symbol carries those signals.
         *
         * Returns the original name unchanged when finance/ has nothing for this code.
         */
        private fun prefixNameWithEvents(baseName: String, code: String): String {
            return try {
                // One snapshot read for both lookups — two reads could straddle a
                // file-watcher reload and mix events/grade from different states.
                val snapshot = FinanceBridgeService.instance.snapshot()
                val key = FinanceSymbol.normalize(code)
                val events = snapshot.eventsBySymbol[key] ?: emptySet()
                val gradeGlyph = snapshot.entryTimingBySymbol[key]?.gradeGlyph

                val sb = StringBuilder()
                if (gradeGlyph != null) sb.append(gradeGlyph).append(' ')
                if (FinanceEventCalendar.EventKind.EARNINGS in events) sb.append("📊")
                if (FinanceEventCalendar.EventKind.UNLOCK in events) sb.append("🔓")
                if (sb.isEmpty()) return baseName
                if (sb.last() != ' ') sb.append(' ')
                sb.append(baseName)
                sb.toString()
            } catch (_: Exception) {
                baseName
            }
        }

        /**
         * Encode the finance/ health badge as "<glyph>|<tooltip>" so the cell renderer can show
         * the dot and the explanation in a tooltip without needing a second column.
         * Returns null when the symbol is unknown to finance/ — the renderer will paint a gray dot.
         */
        private fun formatHealthBadge(code: String): String? {
            return try {
                val bridge = FinanceBridgeService.instance
                val h = bridge.healthOf(code)
                val entry = bridge.watchlistEntry(code)
                val rec = bridge.snapshot().entryTimingBySymbol[FinanceSymbol.normalize(code)]

                val tip = StringBuilder()
                if (entry != null) {
                    if (entry.name != null) {
                        tip.append(entry.name).append(" (").append(entry.symbol).append(")\n")
                    }
                    if (entry.sector != null) {
                        tip.append(StockerBundle.message("health.tooltip.sector", entry.sector)).append('\n')
                    }
                    if (entry.targetZoneLow != null && entry.targetZoneHigh != null) {
                        tip.append(StockerBundle.message("health.tooltip.target", entry.targetZoneLow, entry.targetZoneHigh)).append('\n')
                    }
                    if (entry.trigger != null) {
                        tip.append(StockerBundle.message("health.tooltip.trigger", entry.trigger)).append('\n')
                    }
                }
                if (rec != null) {
                    tip.append("──── entry-timing ────\n")
                    if (rec.grade != null) {
                        tip.append(StockerBundle.message("health.tooltip.grade", rec.grade))
                        if (rec.entryType != null) tip.append(" · ").append(rec.entryType)
                        tip.append('\n')
                    }
                    if (rec.triggerPrice != null) {
                        tip.append(StockerBundle.message("health.tooltip.trigger", String.format("¥%.2f", rec.triggerPrice))).append('\n')
                    }
                    if (rec.invalidationPrice != null) {
                        tip.append(StockerBundle.message("health.tooltip.invalidation", String.format("¥%.2f", rec.invalidationPrice))).append('\n')
                    }
                    if (rec.firstPositionPct != null) {
                        tip.append(StockerBundle.message("health.tooltip.first.position", rec.firstPositionPct))
                        if (rec.addSchedule != null) {
                            tip.append(" · ").append(StockerBundle.message("health.tooltip.add.schedule", rec.addSchedule))
                        }
                        tip.append('\n')
                    }
                    if (rec.alignedThread != null) {
                        tip.append(StockerBundle.message("health.tooltip.thread", rec.alignedThread))
                        if (rec.threadPhase != null) tip.append(" (").append(rec.threadPhase).append(")")
                        tip.append('\n')
                    }
                }
                val glyph = when (h) {
                    FinanceState.Health.GREEN -> {
                        if (tip.isEmpty()) tip.append(StockerBundle.message("health.tooltip.green"))
                        "G"
                    }
                    FinanceState.Health.YELLOW -> {
                        if (tip.isEmpty()) tip.append(StockerBundle.message("health.tooltip.yellow"))
                        else tip.insert(0, StockerBundle.message("health.tooltip.yellow.prefix") + "\n")
                        "Y"
                    }
                    FinanceState.Health.RED -> {
                        if (tip.isEmpty()) tip.append(StockerBundle.message("health.tooltip.red"))
                        else tip.insert(0, StockerBundle.message("health.tooltip.red.prefix") + "\n")
                        "R"
                    }
                    FinanceState.Health.UNKNOWN -> {
                        if (entry == null && rec == null) return null
                        if (tip.isEmpty()) tip.append(StockerBundle.message("health.tooltip.unknown"))
                        "-"
                    }
                }
                "$glyph|${tip.toString().trim()}"
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Build a new row with cells placed by resolved column index — no positional
         * assumptions about the enum order. Columns absent from the model resolve to -1
         * and their values are dropped; the sparkline cell stays null until the
         * intraday fetch populates it.
         */
        private fun buildRow(
            quote: StockerQuote,
            displayName: String,
            costPrice: Double?,
            holdings: Int?,
            columnCount: Int,
            cols: QuoteColumns,
        ): Array<Any?> {
            val row = arrayOfNulls<Any?>(columnCount)
            fun put(col: Int, value: Any?) {
                if (col in 0 until columnCount) row[col] = value
            }
            put(cols.symbol, quote.code)
            put(cols.name, displayName)
            put(cols.current, quote.current)
            put(cols.opening, quote.opening)
            put(cols.close, quote.close)
            put(cols.low, quote.low)
            put(cols.high, quote.high)
            put(cols.change, quote.change)
            put(cols.percent, "${quote.percentage}%")
            put(cols.costPrice, StockerNumberFormat.formatPrice(costPrice))
            put(cols.holdings, StockerNumberFormat.formatHoldings(holdings))
            put(cols.netProfit, StockerNumberFormat.formatNetProfit(quote.current, costPrice, holdings))
            put(cols.health, formatHealthBadge(quote.code))
            put(cols.distance, FinanceDistanceAnnotator.encode(
                FinanceDistanceAnnotator.annotate(quote.code, quote.current)))
            put(cols.updateTime, formatUpdateTime(quote.updateAt))
            return row
        }

        /**
         * Canonical "yyyy-MM-dd HH:mm:ss" stamps collapse to the time part; provider-specific
         * formats (e.g. Sina's US strings) are shown as-is rather than mis-sliced.
         */
        private fun formatUpdateTime(updateAt: String): String {
            return if (updateAt.length == 19 && updateAt[4] == '-' && updateAt[10] == ' ') {
                updateAt.substring(11)
            } else {
                updateAt
            }
        }
    }
}
