package com.vermouthx.stocker.components

import com.intellij.ui.JBColor
import com.vermouthx.stocker.settings.StockerSetting
import java.awt.Color
import java.awt.Component
import javax.swing.BorderFactory
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableCellRenderer

open class StockerDefaultTableCellRender : DefaultTableCellRenderer() {

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

        horizontalAlignment = SwingConstants.CENTER
        val innerPadding = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        val isLastVisibleColumn = column == table.columnCount - 1
        border = if (isLastVisibleColumn) {
            innerPadding
        } else {
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, table.gridColor),
                innerPadding,
            )
        }

        if (!isSelected) {
            background = table.background
            foreground = if (isFocusedRow(table, row)) FOCUS_FOREGROUND else table.foreground
        }

        return component
    }

    protected fun isFocusedRow(table: JTable, row: Int): Boolean {
        return try {
            // Column 0 in the model is the code
            val codeObj = table.model.getValueAt(row, 0)
            codeObj != null && StockerSetting.instance.isStockFocused(codeObj.toString())
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Apply foreground color, respecting focus state.
     * Focused rows always display in yellow regardless of column-specific color.
     */
    protected fun applyForeground(table: JTable, row: Int, color: Color) {
        foreground = if (isFocusedRow(table, row)) FOCUS_FOREGROUND else color
    }

    /**
     * Center-align the cell and short-circuit when no color logic should run
     * (the row is selected, or focused — focus highlighting is already applied
     * by the base renderer). Returns true if the subclass should `return` early.
     */
    protected fun shouldSkipColoring(table: JTable, isSelected: Boolean, row: Int): Boolean {
        horizontalAlignment = SwingConstants.CENTER
        return isSelected || isFocusedRow(table, row)
    }

    companion object {
        private val FOCUS_FOREGROUND: Color = JBColor(Color(204, 153, 0), Color(255, 204, 0))
    }
}
