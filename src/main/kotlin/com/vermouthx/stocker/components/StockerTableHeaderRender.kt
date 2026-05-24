package com.vermouthx.stocker.components

import com.intellij.ui.JBColor
import com.vermouthx.stocker.enums.StockerSortState
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.UIManager
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellRenderer

class StockerTableHeaderRender : DefaultTableCellRenderer(), TableCellRenderer {

    var sortColumn: Int = -1
        private set
    var sortState: StockerSortState = StockerSortState.NONE
        private set

    fun setSortState(column: Int, state: StockerSortState) {
        this.sortColumn = column
        this.sortState = state
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean,
        hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, false, false, row, column)

        horizontalAlignment = SwingConstants.CENTER
        isOpaque = true
        background = JBColor.namedColor("TableHeader.background", UIManager.getColor("TableHeader.background"))

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

        font = font.deriveFont(Font.BOLD)

        if (column == sortColumn && sortState != StockerSortState.NONE) {
            val indicator = if (sortState == StockerSortState.ASCENDING) " ↑" else " ↓"
            text = (value?.toString() ?: "") + indicator
            foreground = JBColor.namedColor("Label.selectedForeground", foreground)
        } else {
            text = value?.toString() ?: ""
            foreground = JBColor.namedColor("TableHeader.foreground", UIManager.getColor("TableHeader.foreground"))
        }
        return this
    }
}
