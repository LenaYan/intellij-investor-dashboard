package com.vermouthx.stocker.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.intellij.util.messages.MessageBusConnection
import com.vermouthx.stocker.StockerBundle
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_ALL_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.settings.StockerSetting
import javax.swing.SwingUtilities

/**
 * Status-bar mini ticker for focused (★) stocks: shows one focused symbol's price and
 * change per refresh tick and rotates through the focus list, so a glance at the status
 * bar covers the watch set without opening the tool window. Empty (and effectively
 * hidden) when nothing is focused. Click opens the Stocker tool window.
 */
class StockerTickerWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "Stocker.FocusTicker"
    }

    private var statusBar: StatusBar? = null
    private var connection: MessageBusConnection? = null

    @Volatile
    private var text: String = ""

    @Volatile
    private var tooltip: String? = null

    private var rotation = 0

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        connection = ApplicationManager.getApplication().messageBus.connect().apply {
            subscribe(STOCK_ALL_QUOTE_UPDATE_TOPIC, object : StockerQuoteUpdateNotifier {
                override fun syncQuotes(quotes: List<StockerQuote>, size: Int) = refresh(quotes)
                override fun syncIndices(indices: List<StockerQuote>) = Unit
            })
        }
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String = text

    override fun getTooltipText(): String? = tooltip

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<java.awt.event.MouseEvent> = Consumer {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Stocker")?.show(null)
    }

    private fun refresh(quotes: List<StockerQuote>) {
        val setting = StockerSetting.instance
        val focused = quotes.filter { setting.isStockFocused(it.code) }
        if (focused.isEmpty()) {
            if (text.isNotEmpty()) {
                text = ""
                tooltip = null
                update()
            }
            return
        }
        val quote = focused[rotation % focused.size]
        rotation = (rotation + 1) % focused.size
        val sign = if (quote.percentage >= 0) "+" else ""
        text = "★ ${setting.getDisplayName(quote.code, quote.name)} ${quote.current} $sign${quote.percentage}%"
        tooltip = focused.joinToString("\n") {
            val s = if (it.percentage >= 0) "+" else ""
            "${setting.getDisplayName(it.code, it.name)}  ${it.current}  $s${it.percentage}%"
        }
        update()
    }

    private fun update() {
        SwingUtilities.invokeLater {
            statusBar?.updateWidget(ID)
        }
    }
}

class StockerTickerWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = StockerTickerWidget.ID

    override fun getDisplayName(): String = StockerBundle.message("statusbar.ticker.name")

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = StockerTickerWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
