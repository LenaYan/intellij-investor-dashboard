package com.vermouthx.stocker.components

import com.intellij.ui.JBColor
import com.vermouthx.stocker.enums.StockerTableColumn
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.views.StockerQuoteColors
import java.awt.Color
import java.awt.Component
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel

/**
 * Cell renderers for the quote table, extracted from StockerTableView. All directional
 * coloring goes through the shared [StockerQuoteColors] so a color-pattern settings
 * change applies everywhere at once.
 */

/** Value-parsing helpers shared by renderers and the sort controller. */
internal object StockerCellValues {

    fun parseDouble(value: Any?): Double? {
        if (value == null) return null
        return value.toString().toDoubleOrNull()
    }

    fun parsePercentage(percentStr: String?): Double? {
        if (percentStr.isNullOrEmpty()) return null
        return try {
            val percentIndex = percentStr.indexOf('%')
            if (percentIndex > 0) percentStr.substring(0, percentIndex).toDouble()
            else percentStr.toDouble()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Look up the (sibling) column's value on the same row, returns null on any miss. */
    fun siblingValue(table: JTable, row: Int, columnName: String): Any? {
        val m = table.model as? DefaultTableModel ?: return null
        val idx = m.findColumn(columnName)
        if (idx < 0 || row < 0 || row >= m.rowCount) return null
        return m.getValueAt(row, idx)
    }
}

/**
 * Code column: strips the BTC prefix from crypto codes and the SH/SZ/BJ exchange prefix
 * from A-share codes. The exchange prefix is part of the row's canonical identity (so
 * SH000001 vs SZ000001 don't collide), but users only want the bare 6-digit code shown.
 */
internal class StockerCodeCellRenderer : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        horizontalAlignment = DefaultTableCellRenderer.CENTER
        var displayValue: Any? = value
        if (value != null) {
            val code = value.toString()
            if (code.startsWith("BTC") && code.length > 3) {
                displayValue = code.substring(3)
            } else if (code.length > 2) {
                // Only strip SH/SZ/BJ when the remainder is a plain numeric A-share code,
                // so we don't decapitate US tickers that happen to start with SH (SHCO, SHEN).
                val prefix = code.substring(0, 2).uppercase()
                val rest = code.substring(2)
                if ((prefix == "SH" || prefix == "SZ" || prefix == "BJ") && rest.all { it.isDigit() }) {
                    displayValue = rest
                }
            }
        }
        return super.getTableCellRendererComponent(table, displayValue, isSelected, hasFocus, row, column)
    }
}

/** Numeric (Current, Opening, Close, Low, High) — color tracks the row's Change% column. */
internal class StockerNumericCellRenderer(private val colors: StockerQuoteColors) : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (shouldSkipColoring(table, isSelected, row)) return c
        val pct = StockerCellValues.siblingValue(table, row, StockerTableColumn.CHANGE_PERCENT.name)
        foreground = colors.signColor(pct?.let { StockerCellValues.parsePercentage(it.toString()) }, table.foreground)
        return c
    }
}

/** Change column — color follows the value's sign. */
internal class StockerChangeCellRenderer(private val colors: StockerQuoteColors) : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (shouldSkipColoring(table, isSelected, row)) return c
        foreground = colors.signColor(value?.let { StockerCellValues.parseDouble(it) }, table.foreground)
        return c
    }
}

/** Change% column — parse the "%" suffix and color by sign. */
internal class StockerPercentCellRenderer(private val colors: StockerQuoteColors) : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (shouldSkipColoring(table, isSelected, row)) return c
        foreground = colors.signColor(value?.let { StockerCellValues.parsePercentage(it.toString()) }, table.foreground)
        return c
    }
}

/** Cost column — colored by position P&L: current above cost paints the up color. */
internal class StockerCostCellRenderer(private val colors: StockerQuoteColors) : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (shouldSkipColoring(table, isSelected, row)) return c
        val cost = value?.let { StockerCellValues.parseDouble(it) }
        val cur = StockerCellValues.siblingValue(table, row, StockerTableColumn.CURRENT.name)
        val curPrice = cur?.let { StockerCellValues.parseDouble(it) }
        foreground = if (cost == null || curPrice == null) {
            table.foreground
        } else {
            // positive (curPrice > cost) ⇒ up color (the position is winning)
            colors.signColor(curPrice - cost, table.foreground)
        }
        return c
    }
}

internal class StockerNetProfitCellRenderer(private val colors: StockerQuoteColors) : StockerDefaultTableCellRender() {
    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        if (shouldSkipColoring(table, isSelected, row)) return c
        foreground = colors.signColor(StockerCellValues.parseDouble(value), table.foreground)
        return c
    }
}

/**
 * Health column. Value format `<glyph>|<tooltip>`, supplied by
 * [com.vermouthx.stocker.listeners.StockerQuoteUpdateListener] based on
 * [com.vermouthx.stocker.finance.FinanceBridgeService]. Color reflects the status,
 * not the up/down direction.
 */
internal class StockerHealthCellRenderer : StockerDefaultTableCellRender() {
    private val green = JBColor(Color(0x2E7D32), Color(0x66BB6A))
    private val yellow = JBColor(Color(0xC78A00), Color(0xFFCA28))
    private val red = JBColor(Color(0xC62828), Color(0xEF5350))
    private val gray = JBColor.GRAY

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = DefaultTableCellRenderer.CENTER
        if (isSelected) return component
        if (value == null) {
            foreground = gray
            text = "●"
            toolTipText = null
            return component
        }
        val s = value.toString()
        val sep = s.indexOf('|')
        val glyph = if (sep >= 0) s.substring(0, sep) else s
        val tip = if (sep >= 0) s.substring(sep + 1) else null
        text = "●"
        toolTipText = tip
        foreground = when (glyph) {
            "G" -> green
            "Y" -> yellow
            "R" -> red
            else -> gray
        }
        return component
    }
}

/**
 * DISTANCE column. Cell value format: `<level>|<text>|<tooltip>`, produced by
 * [com.vermouthx.stocker.finance.FinanceDistanceAnnotator].
 *
 * Level mapping:
 *   A (ALERT)  red background  → invalidation breached
 *   W (WARN)   amber background → inside trigger ±1.5% OR within +1.5% of invalidation
 *   I (INFO)   default bg, plain text → within ±5% of trigger
 *   N (NONE)   muted gray text → passive distance (>5% away)
 *
 * WARN/ALERT colors only apply when the corresponding setting is enabled — turning
 * `financeNotifyTriggers` / `financeNotifyEntryTiming` off downgrades all WARN/ALERT
 * to neutral display (still shows the text, just without urgent color).
 */
internal class StockerDistanceCellRenderer : StockerDefaultTableCellRender() {
    private val alertBg = JBColor(Color(0xC62828), Color(0xB71C1C))
    private val alertFg = JBColor(Color.WHITE, Color.WHITE)
    private val warnBg = JBColor(Color(0xFFF1B8), Color(0x5A4B1A))
    private val warnFg = JBColor(Color(0x6B4E00), Color(0xFFD54F))
    private val infoFg = JBColor(Color(0x37474F), Color(0xCFD8DC))
    private val muted = JBColor.GRAY

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        horizontalAlignment = DefaultTableCellRenderer.CENTER
        // Reset background — super-class may have set selection or zebra background
        if (!isSelected) {
            background = table.background
            isOpaque = false
        }
        if (value == null) {
            text = ""
            toolTipText = null
            return component
        }
        val s = value.toString()
        var level = "N"
        var label = s
        var tip: String? = null
        val sep1 = s.indexOf('|')
        if (sep1 >= 0) {
            level = s.substring(0, sep1)
            val rest = s.substring(sep1 + 1)
            val sep2 = rest.indexOf('|')
            if (sep2 >= 0) {
                label = rest.substring(0, sep2)
                tip = rest.substring(sep2 + 1).ifEmpty { null }
            } else {
                label = rest
            }
        }
        text = label
        toolTipText = tip
        if (isSelected) return component

        val setting = StockerSetting.instance
        // Determine if WARN/ALERT colors should be suppressed (user disabled both flags
        // → user explicitly opted out of urgent visuals; still show plain text).
        val suppressUrgent = !setting.financeNotifyTriggers && !setting.financeNotifyEntryTiming

        when {
            !suppressUrgent && "A" == level -> {
                background = alertBg
                foreground = alertFg
                isOpaque = true
            }
            !suppressUrgent && "W" == level -> {
                background = warnBg
                foreground = warnFg
                isOpaque = true
            }
            "I" == level -> foreground = infoFg
            else -> foreground = muted
        }
        return component
    }
}
