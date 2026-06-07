package com.vermouthx.stocker.views

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.components.StockerDefaultTableCellRender
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
    private var upColor: Color = JBColor.foreground()
    private var downColor: Color = JBColor.foreground()
    private var zeroColor: Color = JBColor.foreground()
    private lateinit var tbBody: JBTable
    private lateinit var tbModel: StockerTableModel

    private val cbIndex = ComboBox<String>()
    private val lbIndexValue = JBLabel("", SwingConstants.CENTER)
    private val lbIndexExtent = JBLabel("", SwingConstants.CENTER)
    private val lbIndexPercent = JBLabel("", SwingConstants.CENTER)
    private var indices: List<StockerQuote> = ArrayList()

    // Cache renderers to avoid creating new instances on every refresh
    private val defaultRenderer: StockerDefaultTableCellRender = StockerDefaultTableCellRender()
    private val codeRenderer: StockerDefaultTableCellRender = CodeCellRenderer()
    private val numericRenderer: StockerDefaultTableCellRender = NumericCellRenderer()
    private val changeRenderer: StockerDefaultTableCellRender = ChangeCellRenderer()
    private val percentRenderer: StockerDefaultTableCellRender = PercentCellRenderer()
    private val costRenderer: StockerDefaultTableCellRender = CostCellRenderer()
    private val netProfitRenderer: StockerDefaultTableCellRender = NetProfitCellRenderer()
    private val sparklineRenderer = StockerSparklineCellRenderer()
    private val healthRenderer: StockerDefaultTableCellRender = HealthCellRenderer()
    private val distanceRenderer: StockerDefaultTableCellRender = DistanceCellRenderer()

    // Sorting state
    private var headerRenderer: StockerTableHeaderRender? = null
    private var lastSortColumn: Int = -1
    private var currentSortState: StockerSortState = StockerSortState.NONE
    // Backup data only when sorting is active (cleared when returning to NONE state)
    private var sortBackupData: MutableList<Array<Any?>>? = null
    private var popupTargetCode: String? = null
    private var popupTargetName: String? = null

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
        sortBackupData?.clear()
        sortBackupData = null

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
        when (StockerSetting.instance.quoteColorPattern) {
            StockerQuoteColorPattern.RED_UP_GREEN_DOWN -> {
                upColor = JBColor.RED
                downColor = JBColor.GREEN
                zeroColor = JBColor.GRAY
                sparklineRenderer.redUp = true
            }
            StockerQuoteColorPattern.GREEN_UP_RED_DOWN -> {
                upColor = JBColor.GREEN
                downColor = JBColor.RED
                zeroColor = JBColor.GRAY
                sparklineRenderer.redUp = false
            }
            else -> {
                upColor = JBColor.foreground()
                downColor = JBColor.foreground()
                zeroColor = JBColor.foreground()
                sparklineRenderer.redUp = true
            }
        }
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
            val value = index.percentage
            val color = when {
                value > 0 -> upColor
                value < 0 -> downColor
                else -> zeroColor
            }
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
        val rowPopupMenu = createRowPopupMenu()

        tbBody.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (e.isTemporary || rowPopupMenu.isVisible) return
                tbBody.clearSelection()
            }
        })
        tbBody.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = handleTableMouseEvent(e, rowPopupMenu)
            override fun mouseReleased(e: MouseEvent) = handleTableMouseEvent(e, rowPopupMenu)
        })

        tbModel.setColumnIdentifiers(
            arrayOf(
                CODE_COL, NAME_COL, CURRENT_COL, OPENING_COL, CLOSE_COL, LOW_COL, HIGH_COL,
                CHANGE_COL, PERCENT_COL, COST_PRICE_COL, HOLDINGS_COL, NET_PROFIT_COL,
                SPARKLINE_COL, HEALTH_COL, DISTANCE_COL,
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
        headerRenderer = StockerTableHeaderRender().also { tbBody.tableHeader.defaultRenderer = it }

        // Add header click listener for sorting with visual feedback
        tbBody.tableHeader.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val column = tbBody.tableHeader.columnAtPoint(e.point)
                if (column != -1) sortByColumn(column)
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

    private fun handleTableMouseEvent(event: MouseEvent, rowPopupMenu: JPopupMenu) {
        val row = tbBody.rowAtPoint(event.point)
        if (tbBody.isFocusOwner && row == -1 && !event.isPopupTrigger) {
            tbBody.clearSelection()
            return
        }
        if (row != -1 && (event.isPopupTrigger || SwingUtilities.isRightMouseButton(event))) {
            tbBody.setRowSelectionInterval(row, row)
            popupTargetCode = getStringValueAt(row, 0)
            popupTargetName = getStringValueAt(row, 1)
        }
        if (event.isPopupTrigger && row != -1) {
            rowPopupMenu.show(tbBody, event.x, event.y)
        }
    }

    private fun createRowPopupMenu(): JPopupMenu {
        val popupMenu = JPopupMenu()

        fun newItem(text: String?, onClick: () -> Unit): JMenuItem = JMenuItem(text ?: "").apply {
            isOpaque = true
            isRolloverEnabled = true
            border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
            addActionListener { onClick() }
        }

        val focusMenuItem = newItem(null) { toggleFocusSelectedStock() }
        val addWatchlistItem = newItem(StockerBundle.message("menu.add.to.claude.watchlist")) { addSelectedToClaudeWatchlist() }
        val entryTimingItem = newItem(StockerBundle.message("menu.view.entry.timing")) { showSelectedEntryTimingPlan() }
        val bullBearItem = newItem(StockerBundle.message("menu.view.bull.bear")) { showSelectedBullBear() }
        val styleJuryItem = newItem(StockerBundle.message("menu.view.style.jury")) { showSelectedStyleJury() }
        // Delete is only built for editable tabs; the watchlist tab (readOnly) sources its rows
        // from watchlist.json, where the right way to remove an entry is to edit the file.
        val deleteMenuItem = if (readOnly) null else newItem(StockerBundle.message("menu.delete")) { deleteSelectedStock() }

        val defaultBackground = JBColor.namedColor("MenuItem.background", UIManager.getColor("MenuItem.background"))
        val defaultForeground = JBColor.namedColor("MenuItem.foreground", UIManager.getColor("MenuItem.foreground"))
        val hoverBackground = JBColor.namedColor(
            "MenuItem.selectionBackground",
            JBColor.namedColor("List.selectionBackground", tbBody.selectionBackground),
        )
        val hoverForeground = JBColor.namedColor(
            "MenuItem.selectionForeground",
            JBColor.namedColor("List.selectionForeground", tbBody.selectionForeground),
        )

        val items = listOfNotNull(focusMenuItem, addWatchlistItem, entryTimingItem, bullBearItem, styleJuryItem, deleteMenuItem)
        for (item in items) {
            item.background = defaultBackground
            item.foreground = defaultForeground
            item.model.addChangeListener {
                val model: ButtonModel = item.model
                val hovering = model.isArmed || model.isRollover
                item.background = if (hovering) hoverBackground else defaultBackground
                item.foreground = if (hovering) hoverForeground else defaultForeground
            }
        }

        popupMenu.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: PopupMenuEvent) {
                val code = popupTargetCode
                focusMenuItem.text = if (code != null && StockerSetting.instance.isStockFocused(code)) {
                    StockerBundle.message("menu.unfocus")
                } else {
                    StockerBundle.message("menu.focus")
                }
            }
            override fun popupMenuWillBecomeInvisible(e: PopupMenuEvent) = clearPopupStateLater(popupMenu)
            override fun popupMenuCanceled(e: PopupMenuEvent) = clearPopupStateLater(popupMenu)
        })
        popupMenu.add(focusMenuItem)
        popupMenu.addSeparator()
        popupMenu.add(addWatchlistItem)
        popupMenu.add(entryTimingItem)
        popupMenu.add(bullBearItem)
        popupMenu.add(styleJuryItem)
        if (deleteMenuItem != null) {
            popupMenu.addSeparator()
            popupMenu.add(deleteMenuItem)
        }
        return popupMenu
    }

    /**
     * Resolve (code, name) from the popup target (if a right-click set them) or from the
     * currently selected row, then hand them to [action]. Clears popup state in all paths,
     * including when no row is resolvable. The 6 popup actions previously open-coded this.
     */
    private fun withSelectedRow(action: (String, String?) -> Unit) {
        try {
            var code = popupTargetCode
            var name = popupTargetName
            if (code == null) {
                val selectedRow = tbBody.selectedRow
                if (selectedRow < 0) return
                code = getStringValueAt(selectedRow, 0)
                name = getStringValueAt(selectedRow, 1)
            }
            if (code == null) return
            action(code, name)
        } finally {
            clearPopupTarget()
        }
    }

    private fun showSelectedBullBear() = withSelectedRow { code, name ->
        FinanceReportActions.showBullBear(tbBody, code, name)
    }

    private fun showSelectedStyleJury() = withSelectedRow { code, name ->
        FinanceReportActions.showStyleJury(tbBody, code, name)
    }

    private fun showSelectedEntryTimingPlan() = withSelectedRow { code, name ->
        FinanceEntryTimingActions.showPopup(tbBody, code, name)
    }

    private fun addSelectedToClaudeWatchlist() = withSelectedRow { code, name ->
        // Find current price from the selected row (column 2 in the model).
        val row = tbBody.selectedRow
        val refPrice = if (row >= 0) parseDouble(tbModel.getValueAt(row, 2)) else null
        FinanceWatchlistActions.addToWatchlist(code, name, refPrice)
    }

    private fun toggleFocusSelectedStock() = withSelectedRow { code, _ ->
        StockerSetting.instance.toggleFocusStock(code)
        tbBody.repaint()
    }

    private fun deleteSelectedStock() = withSelectedRow { code, name ->
        val market = StockerSetting.instance.marketOf(code)
        if (market == null) {
            // Row exists in the ALL table but not in any favorites list — it was merged in
            // from watchlist.json via FinanceBridgeService.watchlistCodesByMarket(). Delete
            // here would have nowhere to write; tell the user where to remove it instead of
            // failing silently.
            Messages.showInfoMessage(
                tbBody,
                StockerBundle.message("dialog.delete.watchlist.only.message", name ?: code),
                StockerBundle.message("dialog.delete.cannot.delete.title"),
            )
            return@withSelectedRow
        }
        StockerActionUtil.removeStock(market, StockerSuggestion(code, name ?: code, market))
    }

    private fun getStringValueAt(row: Int, column: Int): String? =
        tbModel.getValueAt(row, column)?.toString()

    private fun clearPopupTarget() {
        popupTargetCode = null
        popupTargetName = null
    }

    private fun clearPopupStateLater(popupMenu: JPopupMenu) {
        SwingUtilities.invokeLater {
            if (popupMenu.isVisible) return@invokeLater
            clearPopupTarget()
            if (!tbBody.isFocusOwner) tbBody.clearSelection()
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

            val currentPrice = parseDouble(tbModel.getValueAt(row, currentColumnIndex))
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

    private fun parsePercentage(percentStr: String?): Double? {
        if (percentStr.isNullOrEmpty()) return null
        return try {
            val percentIndex = percentStr.indexOf('%')
            if (percentIndex > 0) percentStr.substring(0, percentIndex).toDouble()
            else percentStr.toDouble()
        } catch (_: NumberFormatException) {
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
        currentSortState = StockerSortState.NONE
        lastSortColumn = -1
        sortBackupData?.clear()
        sortBackupData = null
        headerRenderer?.setSortState(-1, StockerSortState.NONE)
        if (::tbBody.isInitialized && tbBody.tableHeader != null) {
            tbBody.tableHeader.repaint()
        }
    }

    private fun sortByColumn(column: Int) {
        val columnName = tbBody.getColumnName(column)

        // Cycle through sort states: NONE -> ASCENDING -> DESCENDING -> NONE
        if (column == lastSortColumn) {
            currentSortState = when (currentSortState) {
                StockerSortState.NONE -> StockerSortState.ASCENDING
                StockerSortState.ASCENDING -> StockerSortState.DESCENDING
                StockerSortState.DESCENDING -> StockerSortState.NONE
            }
        } else {
            lastSortColumn = column
            currentSortState = StockerSortState.ASCENDING
        }

        headerRenderer?.setSortState(column, currentSortState)
        tbBody.tableHeader.repaint()
        sortTableData(columnName, currentSortState)
    }

    /**
     * Sort visible rows by `columnName` in the given direction, or restore the original order
     * when state == NONE. Keeps a single backup of the unsorted row data while sorting is
     * active so the user can cycle ASC→DESC→NONE without losing original order. Note: this
     * does a full row-copy on each call; for very large tables consider a TableRowSorter.
     */
    private fun sortTableData(columnName: String, sortState: StockerSortState) {
        val rowCount = tbModel.rowCount
        if (rowCount == 0) return

        // For NONE state, restore original data and clear backup
        if (sortState == StockerSortState.NONE) {
            val backup = sortBackupData
            if (backup != null && backup.isNotEmpty()) {
                tbModel.rowCount = 0
                for (row in backup) tbModel.addRow(row)
                backup.clear()
                sortBackupData = null
            }
            return
        }

        // Capture original data before first sort (only once)
        if (sortBackupData == null) {
            val captured = ArrayList<Array<Any?>>(rowCount)
            for (i in 0 until rowCount) {
                val row = arrayOfNulls<Any?>(tbModel.columnCount)
                for (j in 0 until tbModel.columnCount) row[j] = tbModel.getValueAt(i, j)
                captured.add(row)
            }
            sortBackupData = captured
        }

        // Get the column index in the model
        var columnIndex = -1
        for (i in 0 until tbModel.columnCount) {
            if (tbModel.getColumnName(i) == columnName) {
                columnIndex = i
                break
            }
        }
        if (columnIndex == -1) return

        // Skip sorting for non-sortable columns (sparkline, health, distance)
        if (columnName == SPARKLINE_COL || columnName == HEALTH_COL || columnName == DISTANCE_COL) return

        val sortColumnIndex = columnIndex
        val ascending = sortState == StockerSortState.ASCENDING

        // Sort indices based on values - only references are sorted, not actual data
        val sortedIndices = (0 until rowCount).sortedWith(Comparator { i1, i2 ->
            val val1 = tbModel.getValueAt(i1, sortColumnIndex)
            val val2 = tbModel.getValueAt(i2, sortColumnIndex)
            val result = when (columnName) {
                CODE_COL, NAME_COL -> {
                    val s1 = val1?.toString() ?: ""
                    val s2 = val2?.toString() ?: ""
                    s1.compareTo(s2, ignoreCase = true)
                }
                PERCENT_COL -> {
                    val p1 = parsePercentage(val1?.toString())
                    val p2 = parsePercentage(val2?.toString())
                    compareNullable(p1, p2)
                }
                else -> {
                    val n1 = parseDouble(val1)
                    val n2 = parseDouble(val2)
                    compareNullable(n1, n2)
                }
            }
            if (ascending) result else -result
        })

        // Reorder rows based on sorted indices
        val sortedRows = ArrayList<Array<Any?>>(rowCount)
        for (sourceIndex in sortedIndices) {
            val row = arrayOfNulls<Any?>(tbModel.columnCount)
            for (j in 0 until tbModel.columnCount) row[j] = tbModel.getValueAt(sourceIndex, j)
            sortedRows.add(row)
        }

        tbModel.rowCount = 0
        for (row in sortedRows) tbModel.addRow(row)
    }

    private fun <T : Comparable<T>> compareNullable(a: T?, b: T?): Int = when {
        a != null && b != null -> a.compareTo(b)
        a != null -> 1
        b != null -> -1
        else -> 0
    }

    private fun parseDouble(value: Any?): Double? {
        if (value == null) return null
        return value.toString().toDoubleOrNull()
    }

    /** Pick up/down/zero/default color for a possibly-null direction value. */
    private fun signColor(value: Double?, fallback: Color): Color = when {
        value == null -> fallback
        value > 0 -> upColor
        value < 0 -> downColor
        else -> zeroColor
    }

    /** Look up the (sibling) column's value on the same row, returns null on any miss. */
    private fun siblingValue(table: JTable, row: Int, columnName: String): Any? {
        val m = table.model as? DefaultTableModel ?: return null
        val idx = m.findColumn(columnName)
        if (idx < 0 || row < 0 || row >= m.rowCount) return null
        return m.getValueAt(row, idx)
    }

    // Inner class for Code column renderer that strips the BTC prefix from crypto codes
    // and the SH/SZ/BJ exchange prefix from A-share codes. The exchange prefix is part of
    // the row's canonical identity (so SH000001 vs SZ000001 don't collide), but users only
    // want the bare 6-digit code in the table.
    private inner class CodeCellRenderer : StockerDefaultTableCellRender() {
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

    // Numeric (Current, Opening, Close, Low, High) — color tracks the row's Change% column.
    private inner class NumericCellRenderer : StockerDefaultTableCellRender() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (shouldSkipColoring(table, isSelected, row)) return c
            val pct = siblingValue(table, row, PERCENT_COL)
            foreground = signColor(pct?.let { parsePercentage(it.toString()) }, table.foreground)
            return c
        }
    }

    // Change column — color follows the value's sign.
    private inner class ChangeCellRenderer : StockerDefaultTableCellRender() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (shouldSkipColoring(table, isSelected, row)) return c
            foreground = signColor(value?.let { parseDouble(it) }, table.foreground)
            return c
        }
    }

    // Change% column — parse the "%" suffix and color by sign.
    private inner class PercentCellRenderer : StockerDefaultTableCellRender() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (shouldSkipColoring(table, isSelected, row)) return c
            foreground = signColor(value?.let { parsePercentage(it.toString()) }, table.foreground)
            return c
        }
    }

    // Cost column — inverted: cost > current → down color (you're underwater on a position).
    private inner class CostCellRenderer : StockerDefaultTableCellRender() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (shouldSkipColoring(table, isSelected, row)) return c
            val cost = value?.let { parseDouble(it) }
            val cur = siblingValue(table, row, CURRENT_COL)
            val curPrice = cur?.let { parseDouble(it) }
            foreground = if (cost == null || curPrice == null) {
                table.foreground
            } else {
                // positive (curPrice > cost) ⇒ up color (the position is winning)
                signColor(curPrice - cost, table.foreground)
            }
            return c
        }
    }

    private inner class NetProfitCellRenderer : StockerDefaultTableCellRender() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
        ): Component {
            val c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (shouldSkipColoring(table, isSelected, row)) return c
            foreground = signColor(parseDouble(value), table.foreground)
            return c
        }
    }

    /**
     * Renderer for the Health column. Value format `<glyph>|<tooltip>`, supplied by
     * [com.vermouthx.stocker.listeners.StockerQuoteUpdateListener] based on
     * [com.vermouthx.stocker.finance.FinanceBridgeService]. Color reflects the status,
     * not the up/down direction.
     */
    private inner class HealthCellRenderer : StockerDefaultTableCellRender() {
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
     * Renderer for the DISTANCE column. Cell value format: `<level>|<text>|<tooltip>`,
     * produced by [com.vermouthx.stocker.finance.FinanceDistanceAnnotator].
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
    private inner class DistanceCellRenderer : StockerDefaultTableCellRender() {
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
