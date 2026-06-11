package com.vermouthx.stocker.views

import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.table.JBTable
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.components.StockerCellValues
import com.vermouthx.stocker.components.StockerTableModel
import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.finance.FinanceEntryTimingActions
import com.vermouthx.stocker.finance.FinanceReportActions
import com.vermouthx.stocker.finance.FinanceWatchlistActions
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerActionUtil
import com.vermouthx.stocker.views.dialogs.StockerPriceAlertDialog
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.ButtonModel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener

/**
 * Right-click context menu for quote-table rows plus the row actions it triggers
 * (focus toggle, price alert, finance views, delete), extracted from StockerTableView.
 * Tracks the popup-target row independently of the selection so actions resolve the
 * row that was actually right-clicked.
 */
internal class StockerTableRowPopup(
    private val table: JBTable,
    private val model: StockerTableModel,
    private val readOnly: Boolean,
) {

    private var popupTargetCode: String? = null
    private var popupTargetName: String? = null

    private val menu: JPopupMenu = createRowPopupMenu()

    /** Whether the popup is currently showing (used by the table's focus handling). */
    val isVisible: Boolean get() = menu.isVisible

    /** Mouse press/release handler: row selection, popup target capture, popup trigger. */
    fun handleMouseEvent(event: MouseEvent) {
        val row = table.rowAtPoint(event.point)
        if (table.isFocusOwner && row == -1 && !event.isPopupTrigger) {
            table.clearSelection()
            return
        }
        if (row != -1 && (event.isPopupTrigger || SwingUtilities.isRightMouseButton(event))) {
            table.setRowSelectionInterval(row, row)
            popupTargetCode = getStringValueAt(row, 0)
            popupTargetName = getStringValueAt(row, 1)
        }
        if (event.isPopupTrigger && row != -1) {
            menu.show(table, event.x, event.y)
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
        val priceAlertItem = newItem(StockerBundle.message("menu.price.alert")) { setPriceAlertForSelectedStock() }
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
            JBColor.namedColor("List.selectionBackground", table.selectionBackground),
        )
        val hoverForeground = JBColor.namedColor(
            "MenuItem.selectionForeground",
            JBColor.namedColor("List.selectionForeground", table.selectionForeground),
        )

        val items = listOfNotNull(focusMenuItem, priceAlertItem, addWatchlistItem, entryTimingItem, bullBearItem, styleJuryItem, deleteMenuItem)
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
        popupMenu.add(priceAlertItem)
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
     * including when no row is resolvable.
     */
    private fun withSelectedRow(action: (String, String?) -> Unit) {
        try {
            var code = popupTargetCode
            var name = popupTargetName
            if (code == null) {
                val selectedRow = table.selectedRow
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

    private fun setPriceAlertForSelectedStock() = withSelectedRow { code, name ->
        val dialog = StockerPriceAlertDialog(code, name)
        if (dialog.showAndGet()) {
            StockerSetting.instance.setPriceAlerts(code, dialog.aboveValue, dialog.belowValue)
        }
    }

    private fun showSelectedBullBear() = withSelectedRow { code, name ->
        FinanceReportActions.showBullBear(table, code, name)
    }

    private fun showSelectedStyleJury() = withSelectedRow { code, name ->
        FinanceReportActions.showStyleJury(table, code, name)
    }

    private fun showSelectedEntryTimingPlan() = withSelectedRow { code, name ->
        FinanceEntryTimingActions.showPopup(table, code, name)
    }

    private fun addSelectedToClaudeWatchlist() = withSelectedRow { code, name ->
        // Find current price from the selected row (column 2 in the model).
        val row = table.selectedRow
        val refPrice = if (row >= 0) StockerCellValues.parseDouble(model.getValueAt(row, 2)) else null
        FinanceWatchlistActions.addToWatchlist(code, name, refPrice)
    }

    private fun toggleFocusSelectedStock() = withSelectedRow { code, _ ->
        StockerSetting.instance.toggleFocusStock(code)
        table.repaint()
    }

    private fun deleteSelectedStock() = withSelectedRow { code, name ->
        val market = StockerSetting.instance.marketOf(code)
        if (market == null) {
            // Row exists in the ALL table but not in any favorites list — it was merged in
            // from watchlist.json via FinanceBridgeService.watchlistCodesByMarket(). Delete
            // here would have nowhere to write; tell the user where to remove it instead of
            // failing silently.
            Messages.showInfoMessage(
                table,
                StockerBundle.message("dialog.delete.watchlist.only.message", name ?: code),
                StockerBundle.message("dialog.delete.cannot.delete.title"),
            )
            return@withSelectedRow
        }
        StockerActionUtil.removeStock(market, StockerSuggestion(code, name ?: code, market))
    }

    private fun getStringValueAt(row: Int, column: Int): String? =
        model.getValueAt(row, column)?.toString()

    private fun clearPopupTarget() {
        popupTargetCode = null
        popupTargetName = null
    }

    private fun clearPopupStateLater(popupMenu: JPopupMenu) {
        SwingUtilities.invokeLater {
            if (popupMenu.isVisible) return@invokeLater
            clearPopupTarget()
            if (!table.isFocusOwner) table.clearSelection()
        }
    }
}
