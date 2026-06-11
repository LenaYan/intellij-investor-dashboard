package com.vermouthx.stocker.views.windows

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.vermouthx.stocker.actions.StockerRefreshAction
import com.vermouthx.stocker.actions.StockerSettingAction
import com.vermouthx.stocker.actions.StockerStockManageAction
import com.vermouthx.stocker.actions.StockerStockSearchAction
import com.vermouthx.stocker.actions.StockerStopAction
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.finance.panels.FinanceMainThreadHeader
import com.vermouthx.stocker.listeners.StockerRefreshState
import com.vermouthx.stocker.listeners.StockerRefreshStatus
import com.vermouthx.stocker.views.StockerTableView
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.SwingUtilities

class StockerSimpleToolWindow(readOnly: Boolean = false) : SimpleToolWindowPanel(true) {
    var tableView: StockerTableView = StockerTableView(readOnly = readOnly)
    private val mainThreadHeader = FinanceMainThreadHeader()

    private val statusLabel = javax.swing.JLabel(" ").apply {
        border = JBUI.Borders.empty(2, 8)
        foreground = JBColor.GRAY
        font = JBUI.Fonts.smallFont()
    }

    fun disposeFinance() {
        mainThreadHeader.dispose()
    }

    /** Render the refresh state in the footer; callable from any thread. */
    fun updateRefreshStatus(status: StockerRefreshStatus) {
        val updatedAt = status.lastSuccessAt
            ?.atZone(ZoneId.systemDefault())
            ?.format(TIME_FORMAT)
            ?: "--"
        val text = when (status.state) {
            StockerRefreshState.LIVE ->
                StockerBundle.message("status.refresh.live", status.intervalSeconds, updatedAt)
            StockerRefreshState.OFF_HOURS ->
                StockerBundle.message("status.refresh.offhours", updatedAt)
            StockerRefreshState.PAUSED ->
                StockerBundle.message("status.refresh.paused")
            StockerRefreshState.BACKOFF ->
                StockerBundle.message("status.refresh.backoff", updatedAt)
        }
        SwingUtilities.invokeLater {
            if (statusLabel.text != text) statusLabel.text = text
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }

    init {
        val actionManager = ActionManager.getInstance()
        val leftActions = listOfNotNull(
            StockerStockSearchAction::class.qualifiedName?.let { actionManager.getAction(it) },
            StockerRefreshAction::class.qualifiedName?.let { actionManager.getAction(it) },
            StockerStopAction::class.qualifiedName?.let { actionManager.getAction(it) },
            StockerStockManageAction::class.qualifiedName?.let { actionManager.getAction(it) }
        )
        val actionGroup = DefaultActionGroup(leftActions)
        val actionToolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, actionGroup, true)
        actionToolbar.targetComponent = tableView.component
        
        val rightActionGroup = DefaultActionGroup().apply {
            StockerSettingAction::class.qualifiedName?.let { actionManager.getAction(it) }?.let { add(it) }
        }
        val rightActionToolbar = actionManager.createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, rightActionGroup, true)
        rightActionToolbar.targetComponent = tableView.component
        
        val toolbarPanel = com.intellij.ui.components.panels.HorizontalLayout(0).let { layout ->
            javax.swing.JPanel(java.awt.BorderLayout()).apply {
                add(actionToolbar.component, java.awt.BorderLayout.WEST)
                add(rightActionToolbar.component, java.awt.BorderLayout.EAST)
            }
        }
        
        this.toolbar = toolbarPanel

        // Wrap table view: main-thread header (auto-hides when finance/ has no data) on top,
        // the existing table component fills the rest.
        val centerPane = javax.swing.JPanel(java.awt.BorderLayout()).apply {
            add(mainThreadHeader, java.awt.BorderLayout.NORTH)
            add(tableView.component, java.awt.BorderLayout.CENTER)
            add(statusLabel, java.awt.BorderLayout.SOUTH)
        }
        setContent(centerPane)
    }
}
