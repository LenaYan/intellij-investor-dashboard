package com.vermouthx.stocker.views.windows

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection
import com.vermouthx.stocker.StockerApp
import com.vermouthx.stocker.StockerAppManager
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.panels.FinanceToolWindowPanel
import com.vermouthx.stocker.listeners.FavoritesQuoteUpdateListener
import com.vermouthx.stocker.listeners.StockerQuoteDeleteListener
import com.vermouthx.stocker.listeners.StockerQuoteDeleteNotifier.Companion.STOCK_ALL_QUOTE_DELETE_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteReloadListener
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_ALL_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_ALL_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.WatchlistQuoteUpdateListener

class StockerToolWindow : ToolWindowFactory {

    private val messageBus = ApplicationManager.getApplication().messageBus

    private lateinit var allView: StockerSimpleToolWindow
    private lateinit var myApplication: StockerApp
    private var financePanel: FinanceToolWindowPanel? = null
    private var watchlistView: StockerSimpleToolWindow? = null
    private var watchlistRefreshListener: (() -> Unit)? = null
    @Volatile private var toolWindowVisible: Boolean = true
    @Volatile private var ideActive: Boolean = true
    private var toolWindowId: String? = null
    private val messageBusConnections = mutableListOf<MessageBusConnection>()

    override fun init(toolWindow: ToolWindow) {
        super.init(toolWindow)
        // Per-market CN/HK/US/Crypto tabs were dropped — Favorites + Watchlist cover every
        // surface those tabs offered. Per-market data is still fetched once (both tabs read
        // the same merged stream and filter); only the per-market UI is gone.
        allView = StockerSimpleToolWindow(readOnly = false)
        myApplication = StockerApp()
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val contentFactory = ContentFactory.getInstance()
        
        // Create a disposable for cleanup when tool window is closed
        val disposable = Disposer.newDisposable("StockerToolWindow")
        toolWindow.disposable.let { Disposer.register(it, disposable) }
        
        val allContent = contentFactory.createContent(
            allView.component,
            StockerBundle.message("tab.favorites"),
            false
        )
        contentManager.addContent(allContent)

        // Watchlist tab — read-only view of ~/Claude/finance/watchlist.json. Only created
        // when the finance bridge is enabled; sits between Favorites and Finance. Constructed
        // with readOnly=true so the right-click delete menu item is suppressed: the source of
        // truth for these rows is the JSON file, not Stocker settings.
        val setting = com.vermouthx.stocker.settings.StockerSetting.instance
        if (setting.financeBridgeEnabled) {
            val watchlist = StockerSimpleToolWindow(readOnly = true)
            watchlistView = watchlist
            val watchlistContent = contentFactory.createContent(
                watchlist.component,
                StockerBundle.message("tab.watchlist"),
                false
            )
            contentManager.addContent(watchlistContent)

            // When watchlist.json changes, clear the table so the next quote refresh
            // re-populates with only the symbols still in the file. This avoids leaving
            // stale rows behind when the user removes entries from watchlist.json.
            val listener: () -> Unit = {
                javax.swing.SwingUtilities.invokeLater {
                    val model = watchlist.tableView.tableModel
                    if (model.rowCount > 0) model.setRowCount(0)
                }
            }
            FinanceBridgeService.instance.addRefreshListener(listener)
            watchlistRefreshListener = listener
        }

        // Finance tab — only visible when the finance/ bridge is enabled.
        if (setting.financeBridgeEnabled) {
            val finance = FinanceToolWindowPanel()
            financePanel = finance
            val financeContent = contentFactory.createContent(finance.component, "Finance", false)
            contentManager.addContent(financeContent)
        }

        this.subscribeMessage()
        
        // Register cleanup when disposable is disposed
        Disposer.register(disposable) {
            cleanup()
        }
        
        StockerAppManager.register(project, myApplication)

        toolWindowId = toolWindow.id

        // IDE activation listener — application-level bus.
        messageBusConnections.add(
            ApplicationManager.getApplication().messageBus.connect().apply {
                subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) {
                        ideActive = true
                        applyPauseState()
                    }
                    override fun applicationDeactivated(ideFrame: IdeFrame) {
                        ideActive = false
                        applyPauseState()
                    }
                })
            }
        )

        // Tool-window visibility — project-level bus.
        messageBusConnections.add(
            project.messageBus.connect().apply {
                subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                    override fun stateChanged(manager: com.intellij.openapi.wm.ToolWindowManager) {
                        val id = toolWindowId ?: return
                        val tw = manager.getToolWindow(id) ?: return
                        toolWindowVisible = tw.isVisible
                        applyPauseState()
                    }
                })
            }
        )

        myApplication.schedule()
    }
    
    private fun cleanup() {
        // Dispose all table views
        allView.tableView.dispose()
        allView.disposeFinance()
        watchlistView?.tableView?.dispose()
        watchlistView?.disposeFinance()
        watchlistView = null
        watchlistRefreshListener?.let { FinanceBridgeService.instance.removeRefreshListener(it) }
        watchlistRefreshListener = null
        financePanel?.dispose()
        financePanel = null

        // Disconnect all message bus connections
        messageBusConnections.forEach { it.disconnect() }
        messageBusConnections.clear()
    }

    private fun applyPauseState() {
        if (toolWindowVisible && ideActive) {
            myApplication.resume()
        } else {
            myApplication.pause()
        }
    }

    private fun subscribeMessage() {
        // Favorites tab subscribes to the ALL stream and filters to codes in favoritesList.
        messageBusConnections.add(messageBus.connect().apply {
            subscribe(STOCK_ALL_QUOTE_UPDATE_TOPIC, FavoritesQuoteUpdateListener(allView.tableView))
        })
        messageBusConnections.add(messageBus.connect().apply {
            subscribe(STOCK_ALL_QUOTE_DELETE_TOPIC, StockerQuoteDeleteListener(allView.tableView))
        })
        messageBusConnections.add(messageBus.connect().apply {
            subscribe(STOCK_ALL_QUOTE_RELOAD_TOPIC, StockerQuoteReloadListener(allView.tableView))
        })

        // Watchlist tab — same stream, filtered to watchlist.json codes.
        watchlistView?.let { v ->
            messageBusConnections.add(messageBus.connect().apply {
                subscribe(STOCK_ALL_QUOTE_UPDATE_TOPIC, WatchlistQuoteUpdateListener(v.tableView))
            })
            messageBusConnections.add(messageBus.connect().apply {
                subscribe(STOCK_ALL_QUOTE_RELOAD_TOPIC, StockerQuoteReloadListener(v.tableView))
            })
        }
    }
}
