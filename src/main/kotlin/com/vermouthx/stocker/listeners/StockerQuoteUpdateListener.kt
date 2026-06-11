package com.vermouthx.stocker.listeners

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

class StockerQuoteUpdateListener(private val myTableView: StockerTableView) : StockerQuoteUpdateNotifier {

    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
        // Quotes arrive on the refresh executor thread; Swing models must only be
        // mutated on the EDT, so the whole batch is marshalled over in one hop.
        SwingUtilities.invokeLater {
            applyQuotes(quotes, size)
        }
    }

    private fun applyQuotes(quotes: List<StockerQuote>, size: Int) {
        val model = myTableView.tableModel
        val setting = StockerSetting.instance

        // Resolve column indices once — they don't change inside this batch.
        val nameCol      = StockerTableModelUtil.colOf(model, StockerTableColumn.NAME)
        val currentCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.CURRENT)
        val openingCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.OPENING)
        val closeCol     = StockerTableModelUtil.colOf(model, StockerTableColumn.CLOSE)
        val lowCol       = StockerTableModelUtil.colOf(model, StockerTableColumn.LOW)
        val highCol      = StockerTableModelUtil.colOf(model, StockerTableColumn.HIGH)
        val changeCol    = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE)
        val percentCol   = StockerTableModelUtil.colOf(model, StockerTableColumn.CHANGE_PERCENT)
        val costPriceCol = StockerTableModelUtil.colOf(model, StockerTableColumn.COST_PRICE)
        val holdingsCol  = StockerTableModelUtil.colOf(model, StockerTableColumn.HOLDINGS)
        val netProfitCol = StockerTableModelUtil.colOf(model, StockerTableColumn.NET_PROFIT)
        val healthCol    = StockerTableModelUtil.colOf(model, StockerTableColumn.HEALTH)
        val distanceCol  = StockerTableModelUtil.colOf(model, StockerTableColumn.DISTANCE)
        val updateTimeCol = StockerTableModelUtil.colOf(model, StockerTableColumn.UPDATE_TIME)

        // Hold the lock for the whole batch — previously each quote re-acquired it.
        synchronized(model) {
            for (quote in quotes) {
                val code = quote.code
                val displayName = prefixNameWithEvents(setting.getDisplayName(code, quote.name), code)
                val costPrice = setting.getCostPrice(code)
                val holdings = setting.getHoldings(code)

                val rowIndex = StockerTableModelUtil.existAt(model, code)
                if (rowIndex != -1) {
                    try {
                        StockerTableModelUtil.setIfChanged(model, rowIndex, nameCol, displayName)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, currentCol, quote.current)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, openingCol, quote.opening)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, closeCol, quote.close)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, lowCol, quote.low)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, highCol, quote.high)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, changeCol, quote.change)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, percentCol, "${quote.percentage}%")
                        StockerTableModelUtil.setIfChanged(model, rowIndex, costPriceCol, StockerNumberFormat.formatPrice(costPrice))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, holdingsCol, StockerNumberFormat.formatHoldings(holdings))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, netProfitCol,
                            StockerNumberFormat.formatNetProfit(quote.current, costPrice, holdings))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, healthCol, formatHealthBadge(code))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, distanceCol,
                            FinanceDistanceAnnotator.encode(FinanceDistanceAnnotator.annotate(code, quote.current)))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, updateTimeCol, formatUpdateTime(quote.updateAt))
                    } finally {
                        model.fireTableRowsUpdated(rowIndex, rowIndex)
                    }
                } else if (quotes.size <= size) {
                    model.addRow(buildRow(quote, displayName, costPrice, holdings, model.columnCount))
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
                val bridge = FinanceBridgeService.instance
                val key = FinanceSymbol.normalize(code)
                val events = bridge.snapshot().eventsBySymbol[key] ?: emptySet()
                val gradeGlyph = bridge.snapshot().entryTimingBySymbol[key]?.gradeGlyph

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
                        tip.append("行业: ").append(entry.sector).append("\n")
                    }
                    if (entry.targetZoneLow != null && entry.targetZoneHigh != null) {
                        tip.append("target: ¥").append(entry.targetZoneLow)
                            .append(" – ¥").append(entry.targetZoneHigh).append("\n")
                    }
                    if (entry.trigger != null) {
                        tip.append("trigger: ").append(entry.trigger).append("\n")
                    }
                }
                if (rec != null) {
                    tip.append("──── entry-timing ────\n")
                    if (rec.grade != null) {
                        tip.append("grade: ").append(rec.grade)
                        if (rec.entryType != null) tip.append(" · ").append(rec.entryType)
                        tip.append('\n')
                    }
                    if (rec.triggerPrice != null) {
                        tip.append("trigger: ¥").append(String.format("%.2f", rec.triggerPrice)).append('\n')
                    }
                    if (rec.invalidationPrice != null) {
                        tip.append("失效价: ¥").append(String.format("%.2f", rec.invalidationPrice)).append('\n')
                    }
                    if (rec.firstPositionPct != null) {
                        tip.append("首仓: ").append(rec.firstPositionPct).append("%")
                        if (rec.addSchedule != null) tip.append(" · 加仓 ").append(rec.addSchedule)
                        tip.append('\n')
                    }
                    if (rec.alignedThread != null) {
                        tip.append("主线: ").append(rec.alignedThread)
                        if (rec.threadPhase != null) tip.append(" (").append(rec.threadPhase).append(")")
                        tip.append('\n')
                    }
                }
                val glyph = when (h) {
                    FinanceState.Health.GREEN -> {
                        if (tip.isEmpty()) tip.append("持仓 thesis 良好")
                        "G"
                    }
                    FinanceState.Health.YELLOW -> {
                        if (tip.isEmpty()) tip.append("接近触发 / thesis 偏离 1-2")
                        else tip.insert(0, "状态: 关注（接近触发或 thesis 偏离 1-2）\n")
                        "Y"
                    }
                    FinanceState.Health.RED -> {
                        if (tip.isEmpty()) tip.append("触发止损 / thesis 偏离 ≥3")
                        else tip.insert(0, "状态: 警戒（已触发或 thesis 偏离 ≥3）\n")
                        "R"
                    }
                    FinanceState.Health.UNKNOWN -> {
                        if (entry == null && rec == null) return null
                        if (tip.isEmpty()) tip.append("已收录至 finance/，暂无 position-risk-monitor / entry-timing 报告")
                        "-"
                    }
                }
                "$glyph|${tip.toString().trim()}"
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Build a new row in canonical column order. The model is initialized with all columns
         * present (StockerTableView.initTable), so we can assume canonical indices here.
         * If a column is later removed from the model entirely, the corresponding cell stays null.
         */
        private fun buildRow(
            quote: StockerQuote,
            displayName: String,
            costPrice: Double?,
            holdings: Int?,
            columnCount: Int,
        ): Array<Any?> {
            val row = arrayOfNulls<Any?>(columnCount)
            row[0] = quote.code
            row[1] = displayName
            row[2] = quote.current
            row[3] = quote.opening
            row[4] = quote.close
            row[5] = quote.low
            row[6] = quote.high
            row[7] = quote.change
            row[8] = "${quote.percentage}%"
            row[9] = StockerNumberFormat.formatPrice(costPrice)
            row[10] = StockerNumberFormat.formatHoldings(holdings)
            row[11] = StockerNumberFormat.formatNetProfit(quote.current, costPrice, holdings)
            // row[12] sparkline data populated by intraday fetch
            if (columnCount > 13) row[13] = formatHealthBadge(quote.code)
            if (columnCount > 14) {
                row[14] = FinanceDistanceAnnotator.encode(
                    FinanceDistanceAnnotator.annotate(quote.code, quote.current))
            }
            if (columnCount > 15) row[15] = formatUpdateTime(quote.updateAt)
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
