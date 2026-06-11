package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerTableColumn
import javax.swing.table.DefaultTableModel

object StockerTableModelUtil {

    @JvmStatic
    fun existAt(tableModel: DefaultTableModel, code: String?): Int {
        if (code == null) return -1
        val codeCol = tableModel.findColumn(StockerTableColumn.SYMBOL.name).let { if (it < 0) 0 else it }
        for (i in 0 until tableModel.rowCount) {
            val cell = tableModel.getValueAt(i, codeCol)
            if (cell != null && code == cell.toString()) return i
        }
        return -1
    }

    /**
     * Update a cell only when the new value differs from the existing one. The single
     * cell-updated event comes from DefaultTableModel.setValueAt itself — don't fire a
     * second one here. No-op for unknown columns.
     */
    @JvmStatic
    fun setIfChanged(model: DefaultTableModel, row: Int, col: Int, newValue: Any?) {
        if (col < 0 || col >= model.columnCount || row < 0 || row >= model.rowCount) return
        val existing = model.getValueAt(row, col)
        if (existing != newValue) {
            model.setValueAt(newValue, row, col)
        }
    }

    /**
     * One-pass code→row index for batch updates: looking each quote up via [existAt]
     * made a refresh tick O(rows²). Same symbol-column fallback as [existAt].
     */
    @JvmStatic
    fun rowIndexByCode(tableModel: DefaultTableModel): MutableMap<String, Int> {
        val codeCol = tableModel.findColumn(StockerTableColumn.SYMBOL.name).let { if (it < 0) 0 else it }
        val index = HashMap<String, Int>(tableModel.rowCount * 2)
        for (i in 0 until tableModel.rowCount) {
            val cell = tableModel.getValueAt(i, codeCol) ?: continue
            index[cell.toString()] = i
        }
        return index
    }

    /** Resolve a model column index by [StockerTableColumn] enum, returning -1 if absent. */
    @JvmStatic
    fun colOf(model: DefaultTableModel, column: StockerTableColumn): Int =
        model.findColumn(column.name)
}
