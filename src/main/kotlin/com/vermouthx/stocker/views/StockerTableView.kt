package com.vermouthx.stocker.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.components.StockerCellValues
import com.vermouthx.stocker.components.StockerChangeCellRenderer
import com.vermouthx.stocker.components.StockerCodeCellRenderer
import com.vermouthx.stocker.components.StockerCostCellRenderer
import com.vermouthx.stocker.components.StockerDefaultTableCellRender
import com.vermouthx.stocker.components.StockerDistanceCellRenderer
import com.vermouthx.stocker.components.StockerHealthCellRenderer
import com.vermouthx.stocker.components.StockerNetProfitCellRenderer
import com.vermouthx.stocker.components.StockerNumericCellRenderer
import com.vermouthx.stocker.components.StockerPercentCellRenderer
import com.vermouthx.stocker.components.StockerSparklineCellRenderer
import com.vermouthx.stocker.components.StockerTableHeaderRender
import com.vermouthx.stocker.components.StockerTableModel
import com.vermouthx.stocker.entities.StockerIntradayData
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.enums.StockerQuoteColorPattern
import com.vermouthx.stocker.enums.StockerSortState
import com.vermouthx.stocker.enums.StockerTableColumn
import com.vermouthx.stocker.finance.FinanceEntryTimingActions
import com.vermouthx.stocker.finance.FinanceReportActions
import com.vermouthx.stocker.finance.FinanceWatchlistActions
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerActionUtil
import com.vermouthx.stocker.utils.StockerNumberFormat
import com.vermouthx.stocker.utils.StockerPinyinUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.Collections
import javax.swing.BorderFactory
import javax.swing.ButtonModel
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import javax.swing.plaf.basic.BasicTableHeaderUI
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn

class StockerTableView(private val readOnly: Boolean = false) : Disposable {

    private lateinit var mPane: JPanel
    private lateinit var tbPane: JScrollPane
    private val colors = StockerQuoteColors()
    private lateinit var tbBody: JBTable
    private lateinit var tbModel: StockerTableModel

    private val cbIndex = ComboBox<String>()
    private val lbIndexValue = JBLabel("", SwingConstants.CENTER)
    private val lbIndexExtent = JBLabel("", SwingConstants.CENTER)
    private val lbIndexPercent = JBLabel("", SwingConstants.CENTER)
    private var indices: List<StockerQuote> = ArrayList()

    // Cache renderers to avoid creating new instances on every refresh
    private val defaultRenderer: StockerDefaultTableCellRender = StockerDefaultTableCellRender()
    private val codeRenderer: StockerDefaultTableCellRender = StockerCodeCellRenderer()
    private val numericRenderer: StockerDefaultTableCellRender = StockerNumericCellRenderer(colors)
    private val changeRenderer: StockerDefaultTableCellRender = StockerChangeCellRenderer(colors)
    private val percentRenderer: StockerDefaultTableCellRender = StockerPercentCellRenderer(colors)
    private val costRenderer: StockerDefaultTableCellRender = StockerCostCellRenderer(colors)
    private val netProfitRenderer: StockerDefaultTableCellRender = StockerNetProfitCellRenderer(colors)
    private val sparklineRenderer = StockerSparklineCellRenderer()
    private val healthRenderer: StockerDefaultTableCellRender = StockerHealthCellRenderer()
    private val distanceRenderer: StockerDefaultTableCellRender = StockerDistanceCellRenderer()

    private var sortController: StockerTableSortController? = null

    @Volatile
    private var disposed: Boolean = false

    data class VisibleSnapshot(val codes: List<String>)

    @Volatile
    private var visibleSnapshot: VisibleSnapshot = VisibleSnapshot(emptyList())

    init {
        tableViews.add(this)
        syncColorPatternSetting()
        initPane()
        initTable()
    }

    /**
     * Clean up resources and remove this instance from the registry.
     * Called automatically when the parent Disposable is disposed.
     */
    override fun dispose() {
        if (disposed) return
        disposed = true
        tableViews.remove(this)

        // Clear data structures to help with garbage collection
        (indices as? MutableList<*>)?.clear()
        indices = emptyList()
        sortController?.dispose()

        // Clear table model
        if (::tbModel.isInitialized) {
            tbModel.rowCount = 0
        }
    }

    fun syncIndices(indices: List<StockerQuote>) {
        SwingUtilities.invokeLater {
            this.indices = indices
            val setting = StockerSetting.instance

            var shouldRefresh = cbIndex.itemCount != indices.size
            if (!shouldRefresh) {
                for (i in indices.indices) {
                    val displayName = setting.getDisplayName(indices[i].code, indices[i].name)
                    if (displayName != cbIndex.getItemAt(i)) {
                        shouldRefresh = true
                        break
                    }
                }
            }

            if (shouldRefresh && indices.isNotEmpty()) {
                val selectedDisplayName = cbIndex.selectedItem?.toString()
                val selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting)
                cbIndex.removeAllItems()
                indices.forEach { cbIndex.addItem(setting.getDisplayName(it.code, it.name)) }
                if (selectedCode != null) {
                    for (i in indices.indices) {
                        if (indices[i].code == selectedCode) {
                            cbIndex.selectedIndex = i
                            break
                        }
                    }
                } else if (indices.isNotEmpty()) {
                    cbIndex.selectedIndex = 0
                }
            }
            syncColorPatternSetting()
            updateIndex()
        }
    }

    /** Update sparkline intraday data for the given codes. */
    fun syncIntradayData(intradayMap: Map<String, StockerIntradayData>) {
        SwingUtilities.invokeLater {
            synchronized(tbModel) {
                val sparklineColIndex = tbModel.findColumn(SPARKLINE_COL)
                if (sparklineColIndex == -1) return@invokeLater
                for (row in 0 until tbModel.rowCount) {
                    val codeObj = tbModel.getValueAt(row, 0) ?: continue
                    val data = intradayMap[codeObj.toString()] ?: continue
                    tbModel.setValueAt(data, row, sparklineColIndex)
                    tbModel.fireTableCellUpdated(row, sparklineColIndex)
                }
            }
        }
    }

    /** Recompute the visible-rows snapshot. Must be called on the EDT. */
    private fun refreshVisibleSnapshot() {
        val rowCount = tbModel.rowCount
        if (rowCount == 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val rect = tbBody.visibleRect
        if (rect.width <= 0 || rect.height <= 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val firstRaw = tbBody.rowAtPoint(java.awt.Point(0, rect.y))
        val lastRaw = tbBody.rowAtPoint(java.awt.Point(0, rect.y + rect.height - 1))
        val first = (if (firstRaw < 0) 0 else firstRaw) - 5
        val last  = (if (lastRaw  < 0) rowCount - 1 else lastRaw) + 5
        val clampedFirst = first.coerceAtLeast(0)
        val clampedLast  = last.coerceAtMost(rowCount - 1)
        val symbolCol = com.vermouthx.stocker.utils.StockerTableModelUtil
            .colOf(tbModel, com.vermouthx.stocker.enums.StockerTableColumn.SYMBOL)
        if (symbolCol < 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val codes = ArrayList<String>(clampedLast - clampedFirst + 1)
        for (modelRow in clampedFirst..clampedLast) {
            val v = tbModel.getValueAt(modelRow, symbolCol) ?: continue
            codes.add(v.toString())
        }
        visibleSnapshot = VisibleSnapshot(codes)
    }

    private fun syncColorPatternSetting() {
        colors.syncFromSettings()
        sparklineRenderer.redUp = colors.redUp
    }

    private fun updateIndex() {
        if (cbIndex.selectedIndex == -1 || cbIndex.selectedItem == null) return
        val selectedDisplayName = cbIndex.selectedItem.toString()
        val setting = StockerSetting.instance
        val selectedCode = findIndexCodeByDisplayName(selectedDisplayName, setting)

        for (index in indices) {
            val displayName = setting.getDisplayName(index.code, index.name)
            val isSelected = if (selectedCode != null) index.code == selectedCode else displayName == selectedDisplayName
            if (!isSelected) continue
            lbIndexValue.text = index.current.toString()
            lbIndexExtent.text = index.change.toString()
            lbIndexPercent.text = "${index.percentage}%"
            val color = colors.signColor(index.percentage, JBColor.foreground())
            lbIndexValue.foreground = color
            lbIndexExtent.foreground = color
            lbIndexPercent.foreground = color
            return
        }
    }

    private fun findIndexCodeByDisplayName(displayName: String?, setting: StockerSetting): String? {
        if (displayName.isNullOrEmpty()) return null
        for (index in indices) {
            val code = index.code
            val customName = setting.getCustomName(code)
            if (customName != null && customName == displayName) return code
            val originalName = index.name
            if (displayName == originalName) return code
            if (displayName == StockerPinyinUtil.toPinyin(originalName)) return code
        }
        return null
    }

    private fun initPane() {
        tbPane = JBScrollPane().apply {
            border = BorderFactory.createEmptyBorder()
            viewportBorder = BorderFactory.createEmptyBorder()
        }
        tbPane.verticalScrollBar.addAdjustmentListener { e ->
            // Skip the intermediate adjustments while the user is still dragging;
            // the final event after release has valueIsAdjusting == false.
            if (!e.valueIsAdjusting) refreshVisibleSnapshot()
        }
        javax.swing.SwingUtilities.invokeLater { refreshVisibleSnapshot() }

        val iPane = JPanel(GridLayout(1, 4, 8, 0)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                BorderFactory.createEmptyBorder(8, 12, 8, 12),
            )
        }

        // Style the index components
        val indexFont = lbIndexValue.font.deriveFont(Font.BOLD, lbIndexValue.font.size + 1f)
        lbIndexValue.font = indexFont
        lbIndexExtent.font = indexFont
        lbIndexPercent.font = indexFont

        iPane.add(cbIndex)
        iPane.add(lbIndexValue)
        iPane.add(lbIndexExtent)
        iPane.add(lbIndexPercent)
        cbIndex.addItemListener { updateIndex() }
        mPane = JPanel(BorderLayout()).apply {
            add(tbPane, BorderLayout.CENTER)
            add(iPane, BorderLayout.SOUTH)
        }
    }

    private fun initTable() {
        tbModel = StockerTableModel()
        tbBody = JBTable()
        val rowPopup = StockerTableRowPopup(tbBody, tbModel, readOnly)

        tbBody.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (e.isTemporary || rowPopup.isVisible) return
                tbBody.clearSelection()
            }
        })
        tbBody.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = rowPopup.handleMouseEvent(e)
            override fun mouseReleased(e: MouseEvent) = rowPopup.handleMouseEvent(e)
        })

        tbModel.setColumnIdentifiers(
            arrayOf(
                CODE_COL, NAME_COL, CURRENT_COL, OPENING_COL, CLOSE_COL, LOW_COL, HIGH_COL,
                CHANGE_COL, PERCENT_COL, COST_PRICE_COL, HOLDINGS_COL, NET_PROFIT_COL,
                SPARKLINE_COL, HEALTH_COL, DISTANCE_COL, UPDATE_TIME_COL,
            )
        )

        tbBody.model = tbModel
        tbBody.autoCreateColumnsFromModel = false
        updateLocalizedHeaders()

        // Table grid styling
        tbBody.rowHeight = 32
        tbBody.intercellSpacing = Dimension(0, 1)
        tbBody.setShowGrid(true)
        tbBody.showVerticalLines = false
        tbBody.showHorizontalLines = true
        tbBody.gridColor = JBColor.namedColor("Table.gridColor", JBColor.border())
        tbBody.fillsViewportHeight = true
        tbBody.columnModel.columnMargin = 0

        // Use IDE theme colors for selection
        tbBody.selectionBackground = JBColor.namedColor("Table.selectionBackground", UIManager.getColor("Table.selectionBackground"))
        tbBody.selectionForeground = JBColor.namedColor("Table.selectionForeground", UIManager.getColor("Table.selectionForeground"))

        // Avoid extra separator lines from custom LAF header UI; renderer will own divider painting.
        tbBody.tableHeader.setUI(BasicTableHeaderUI())
        tbBody.tableHeader.reorderingAllowed = false
        tbBody.tableHeader.preferredSize = Dimension(tbBody.tableHeader.width, 30)
        tbBody.tableHeader.border = BorderFactory.createEmptyBorder()
        val headerRenderer = StockerTableHeaderRender().also { tbBody.tableHeader.defaultRenderer = it }
        sortController = StockerTableSortController(tbBody, tbModel, headerRenderer)

        // Add header click listener for sorting with visual feedback
        tbBody.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = tbBody.tableHeader.columnAtPoint(e.point)
                if (column != -1) sortController?.sortByColumn(column)
            }
            override fun mouseEntered(e: MouseEvent) {
                tbBody.tableHeader.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            override fun mouseExited(e: MouseEvent) {
                tbBody.tableHeader.cursor = Cursor.getDefaultCursor()
            }
        })

        applyColumnVisibility()
        tbPane.setViewportView(tbBody)

        // Keep visible-snapshot fresh.
        tbBody.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = refreshVisibleSnapshot()
            override fun componentShown(e: java.awt.event.ComponentEvent)   = refreshVisibleSnapshot()
        })
        tbModel.addTableModelListener { e ->
            // Only refresh on structural changes — row insert/delete or full reload.
            // Cell updates (price changes) don't affect which codes are visible.
            val type = e.type
            if (type == javax.swing.event.TableModelEvent.INSERT ||
                type == javax.swing.event.TableModelEvent.DELETE ||
                e.firstRow == javax.swing.event.TableModelEvent.HEADER_ROW
            ) {
                refreshVisibleSnapshot()
            }
        }
    }

    private fun applyColumnVisibility() {
        val visibleColumns = StockerSetting.instance.visibleTableColumns

        tbBody.createDefaultColumnsFromModel()
        updateLocalizedHeaders()

        for (columnName in allColumnNames) {
            if (columnName !in visibleColumns) {
                getColumnIfPresent(columnName)?.let { tbBody.removeColumn(it) }
            }
        }

        // Re-apply after column model rebuild to keep header/body cell geometry in sync.
        tbBody.columnModel.columnMargin = 0
        applyColumnRenderers()
    }

    private fun updateLocalizedHeaders() {
        for (i in 0 until tbBody.columnCount) {
            val tableColumn = tbBody.columnModel.getColumn(i)
            val modelIndex = tableColumn.modelIndex
            if (modelIndex in 0 until StockerTableColumn.entries.size) {
                val col = StockerTableColumn.entries[modelIndex]
                tableColumn.identifier = col.name
                tableColumn.headerValue = col.title
            }
        }
    }

    fun refreshColumnVisibility() {
        applyColumnVisibility()
        tbBody.revalidate()
        tbBody.repaint()
    }

    fun refreshColorPattern() {
        syncColorPatternSetting()
        updateIndex()
        tbBody.revalidate()
        tbBody.repaint()
    }

    fun refreshFinancialColumns() {
        val setting = StockerSetting.instance
        val codeColumnIndex = tbModel.findColumn(CODE_COL)
        val currentColumnIndex = tbModel.findColumn(CURRENT_COL)
        val costPriceColumnIndex = tbModel.findColumn(COST_PRICE_COL)
        val holdingsColumnIndex = tbModel.findColumn(HOLDINGS_COL)
        val netProfitColumnIndex = tbModel.findColumn(NET_PROFIT_COL)

        for (row in 0 until tbModel.rowCount) {
            val codeValue = tbModel.getValueAt(row, codeColumnIndex) ?: continue
            val code = codeValue.toString()

            val costPrice = setting.getCostPrice(code)
            val holdings = setting.getHoldings(code)
            tbModel.setValueAt(StockerNumberFormat.formatPrice(costPrice), row, costPriceColumnIndex)
            tbModel.setValueAt(StockerNumberFormat.formatHoldings(holdings), row, holdingsColumnIndex)

            val currentPrice = StockerCellValues.parseDouble(tbModel.getValueAt(row, currentColumnIndex))
            tbModel.setValueAt(StockerNumberFormat.formatNetProfit(currentPrice, costPrice, holdings), row, netProfitColumnIndex)
        }

        tbModel.fireTableDataChanged()
    }

    private fun applyColumnRenderers() {
        applyRenderer(CODE_COL, codeRenderer)
        applyRenderer(NAME_COL, defaultRenderer)
        applyRenderer(CURRENT_COL, numericRenderer)
        applyRenderer(OPENING_COL, numericRenderer)
        applyRenderer(CLOSE_COL, numericRenderer)
        applyRenderer(LOW_COL, numericRenderer)
        applyRenderer(HIGH_COL, numericRenderer)
        applyRenderer(CHANGE_COL, changeRenderer)
        applyRenderer(PERCENT_COL, percentRenderer)
        applyRenderer(COST_PRICE_COL, costRenderer)
        applyRenderer(HOLDINGS_COL, numericRenderer)
        applyRenderer(NET_PROFIT_COL, netProfitRenderer)

        getColumnIfPresent(SPARKLINE_COL)?.apply {
            cellRenderer = sparklineRenderer
            preferredWidth = 120
            minWidth = 80
        }
        getColumnIfPresent(HEALTH_COL)?.apply {
            cellRenderer = healthRenderer
            preferredWidth = 50
            minWidth = 36
            maxWidth = 72
        }
        getColumnIfPresent(DISTANCE_COL)?.apply {
            cellRenderer = distanceRenderer
            preferredWidth = 180
            minWidth = 110
        }
    }

    private fun applyRenderer(columnIdentifier: String, renderer: TableCellRenderer) {
        getColumnIfPresent(columnIdentifier)?.cellRenderer = renderer
    }

    private fun getColumnIfPresent(identifier: String): TableColumn? {
        return try {
            tbBody.getColumn(identifier)
        } catch (_: IllegalArgumentException) {
            // JTable.getColumn searches by identifier first, fall back to manual search
            for (i in 0 until tbBody.columnCount) {
                val col = tbBody.columnModel.getColumn(i)
                if (identifier == col.identifier) return col
            }
            null
        }
    }

    val component: JComponent get() = mPane
    val tableBody: JBTable get() = tbBody
    val tableModel: DefaultTableModel get() = tbModel

    /**
     * Clears the sort state and resets to unsorted view.
     * Should be called when table data is externally modified.
     */
    fun clearSortState() {
        sortController?.clearSortState()
    }

    companion object {
        private val tableViews: MutableList<StockerTableView> =
            Collections.synchronizedList(ArrayList())

        private val CODE_COL = StockerTableColumn.SYMBOL.name
        private val NAME_COL = StockerTableColumn.NAME.name
        private val CURRENT_COL = StockerTableColumn.CURRENT.name
        private val OPENING_COL = StockerTableColumn.OPENING.name
        private val CLOSE_COL = StockerTableColumn.CLOSE.name
        private val LOW_COL = StockerTableColumn.LOW.name
        private val HIGH_COL = StockerTableColumn.HIGH.name
        private val CHANGE_COL = StockerTableColumn.CHANGE.name
        private val PERCENT_COL = StockerTableColumn.CHANGE_PERCENT.name
        private val COST_PRICE_COL = StockerTableColumn.COST_PRICE.name
        private val HOLDINGS_COL = StockerTableColumn.HOLDINGS.name
        private val NET_PROFIT_COL = StockerTableColumn.NET_PROFIT.name
        private val SPARKLINE_COL = StockerTableColumn.SPARKLINE.name
        private val HEALTH_COL = StockerTableColumn.HEALTH.name
        private val DISTANCE_COL = StockerTableColumn.DISTANCE.name
        private val UPDATE_TIME_COL = StockerTableColumn.UPDATE_TIME.name
        private val allColumnNames: List<String> = StockerTableColumn.entries.map { it.name }

        @JvmStatic
        fun refreshAllColumnVisibility() {
            SwingUtilities.invokeLater {
                synchronized(tableViews) {
                    for (view in tableViews) view.refreshColumnVisibility()
                }
            }
        }

        @JvmStatic
        fun refreshAllColorPatterns() {
            SwingUtilities.invokeLater {
                synchronized(tableViews) {
                    for (view in tableViews) view.refreshColorPattern()
                }
            }
        }

        @JvmStatic
        fun refreshAllFinancialColumns() {
            SwingUtilities.invokeLater {
                synchronized(tableViews) {
                    for (view in tableViews) view.refreshFinancialColumns()
                }
            }
        }

        /** Broadcast intraday data to all active table views. */
        @JvmStatic
        fun syncAllIntradayData(intradayMap: Map<String, StockerIntradayData>) {
            if (intradayMap.isEmpty()) return
            synchronized(tableViews) {
                for (view in tableViews) view.syncIntradayData(intradayMap)
            }
        }

        /** Snapshot of codes currently visible across all active table views, grouped by market. */
        @JvmStatic
        fun visibleCodesByMarket(): Map<com.vermouthx.stocker.enums.StockerMarketType, Set<String>> {
            val setting = com.vermouthx.stocker.settings.StockerSetting.instance
            val grouped = HashMap<com.vermouthx.stocker.enums.StockerMarketType, MutableSet<String>>()
            synchronized(tableViews) {
                for (view in tableViews) {
                    for (code in view.visibleSnapshot.codes) {
                        val m = setting.marketOf(code) ?: continue
                        grouped.getOrPut(m) { HashSet() }.add(code)
                    }
                }
            }
            return grouped
        }
    }
}
