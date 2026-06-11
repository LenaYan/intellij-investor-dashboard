package com.vermouthx.stocker.views

import com.intellij.ui.table.JBTable
import com.vermouthx.stocker.components.StockerCellValues
import com.vermouthx.stocker.components.StockerTableHeaderRender
import com.vermouthx.stocker.components.StockerTableModel
import com.vermouthx.stocker.enums.StockerSortState
import com.vermouthx.stocker.enums.StockerTableColumn

/**
 * Three-state header sorting (NONE → ASC → DESC → NONE) for the quote table,
 * extracted from StockerTableView. Keeps a single backup of the unsorted rows while
 * sorting is active so cycling back to NONE restores the original order. External
 * data changes must call [clearSortState] (the table model is rebuilt by the sort).
 */
class StockerTableSortController(
    private val table: JBTable,
    private val model: StockerTableModel,
    private val headerRenderer: StockerTableHeaderRender,
) {

    private var lastSortColumn: Int = -1
    private var currentSortState: StockerSortState = StockerSortState.NONE

    // Backup data only when sorting is active (cleared when returning to NONE state)
    private var sortBackupData: MutableList<Array<Any?>>? = null

    fun sortByColumn(viewColumn: Int) {
        val columnName = table.getColumnName(viewColumn)

        // Cycle through sort states: NONE -> ASCENDING -> DESCENDING -> NONE
        if (viewColumn == lastSortColumn) {
            currentSortState = when (currentSortState) {
                StockerSortState.NONE -> StockerSortState.ASCENDING
                StockerSortState.ASCENDING -> StockerSortState.DESCENDING
                StockerSortState.DESCENDING -> StockerSortState.NONE
            }
        } else {
            lastSortColumn = viewColumn
            currentSortState = StockerSortState.ASCENDING
        }

        headerRenderer.setSortState(viewColumn, currentSortState)
        table.tableHeader?.repaint()
        sortTableData(columnName, currentSortState)
    }

    /**
     * Clears the sort state and resets to unsorted view.
     * Should be called when table data is externally modified.
     */
    fun clearSortState() {
        currentSortState = StockerSortState.NONE
        lastSortColumn = -1
        sortBackupData?.clear()
        sortBackupData = null
        headerRenderer.setSortState(-1, StockerSortState.NONE)
        table.tableHeader?.repaint()
    }

    /** Release the row backup; for dispose paths only (no header repaint). */
    fun dispose() {
        sortBackupData?.clear()
        sortBackupData = null
    }

    /**
     * Sort visible rows by `columnName` in the given direction, or restore the original order
     * when state == NONE. Note: this does a full row-copy on each call; for very large tables
     * consider a TableRowSorter.
     */
    private fun sortTableData(columnName: String, sortState: StockerSortState) {
        val rowCount = model.rowCount
        if (rowCount == 0) return

        // For NONE state, restore original data and clear backup
        if (sortState == StockerSortState.NONE) {
            val backup = sortBackupData
            if (backup != null && backup.isNotEmpty()) {
                model.rowCount = 0
                for (row in backup) model.addRow(row)
                backup.clear()
                sortBackupData = null
            }
            return
        }

        // Capture original data before first sort (only once)
        if (sortBackupData == null) {
            val captured = ArrayList<Array<Any?>>(rowCount)
            for (i in 0 until rowCount) {
                val row = arrayOfNulls<Any?>(model.columnCount)
                for (j in 0 until model.columnCount) row[j] = model.getValueAt(i, j)
                captured.add(row)
            }
            sortBackupData = captured
        }

        // Get the column index in the model
        var columnIndex = -1
        for (i in 0 until model.columnCount) {
            if (model.getColumnName(i) == columnName) {
                columnIndex = i
                break
            }
        }
        if (columnIndex == -1) return

        // Skip sorting for non-sortable columns (sparkline, health, distance)
        if (columnName == StockerTableColumn.SPARKLINE.name ||
            columnName == StockerTableColumn.HEALTH.name ||
            columnName == StockerTableColumn.DISTANCE.name
        ) {
            return
        }

        val sortColumnIndex = columnIndex
        val ascending = sortState == StockerSortState.ASCENDING

        // Sort indices based on values - only references are sorted, not actual data
        val sortedIndices = (0 until rowCount).sortedWith(Comparator { i1, i2 ->
            val val1 = model.getValueAt(i1, sortColumnIndex)
            val val2 = model.getValueAt(i2, sortColumnIndex)
            val result = when (columnName) {
                StockerTableColumn.SYMBOL.name, StockerTableColumn.NAME.name -> {
                    val s1 = val1?.toString() ?: ""
                    val s2 = val2?.toString() ?: ""
                    s1.compareTo(s2, ignoreCase = true)
                }
                StockerTableColumn.CHANGE_PERCENT.name -> {
                    val p1 = StockerCellValues.parsePercentage(val1?.toString())
                    val p2 = StockerCellValues.parsePercentage(val2?.toString())
                    compareNullable(p1, p2)
                }
                else -> {
                    val n1 = StockerCellValues.parseDouble(val1)
                    val n2 = StockerCellValues.parseDouble(val2)
                    compareNullable(n1, n2)
                }
            }
            if (ascending) result else -result
        })

        // Reorder rows based on sorted indices
        val sortedRows = ArrayList<Array<Any?>>(rowCount)
        for (sourceIndex in sortedIndices) {
            val row = arrayOfNulls<Any?>(model.columnCount)
            for (j in 0 until model.columnCount) row[j] = model.getValueAt(sourceIndex, j)
            sortedRows.add(row)
        }

        model.rowCount = 0
        for (row in sortedRows) model.addRow(row)
    }

    private fun <T : Comparable<T>> compareNullable(a: T?, b: T?): Int = when {
        a != null && b != null -> a.compareTo(b)
        a != null -> 1
        b != null -> -1
        else -> 0
    }
}
