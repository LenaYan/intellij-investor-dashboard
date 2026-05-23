package com.vermouthx.stocker.finance

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import javax.swing.SwingUtilities

/**
 * IDE status bar widget showing the current finance/ main_thread context:
 *
 *   🧭 AI 算力 · 主升 · D7 · H82  💧紧缩
 *
 * Updates on every FinanceBridgeService refresh (file watcher → state reload).
 * Auto-hides when no finance/ data is available.
 *
 * Click → opens the Stocker tool window (where the full Finance tab lives).
 */
class FinanceStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.TextPresentation {

    companion object {
        const val ID = "Stocker.FinanceStatusBar"
    }

    private var statusBar: StatusBar? = null
    private val refreshHook: () -> Unit = { update() }

    override fun ID(): String = ID

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        update()
    }

    override fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
        statusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val snap = FinanceBridgeService.instance.snapshot()
        val mt = snap.mainThread?.takeIf { it.isNotBlank() } ?: return ""
        val phase = snap.threadPhase ?: "?"
        val age = snap.threadAgeDays?.let { "D$it" } ?: ""
        val health = snap.threadHealthSeries.lastOrNull()?.let { "H$it" } ?: ""
        val parts = mutableListOf<String>()
        parts.add("🧭 $mt")
        parts.add(phase)
        if (age.isNotEmpty()) parts.add(age)
        if (health.isNotEmpty()) parts.add(health)

        val liquidity = snap.liquidityEnv
        val liquidGlyph = when {
            liquidity == null -> ""
            liquidity.contains("宽松") -> "🟢"
            liquidity.contains("紧") -> "🔴"
            liquidity.contains("中性") -> "⚪"
            else -> ""
        }
        val drift = if (snap.canonicalDrifts.isNotEmpty()) " ⚠️" else ""
        val core = parts.joinToString(" · ")
        return "$core${if (liquidGlyph.isNotEmpty()) "  $liquidGlyph${liquidity}" else ""}$drift"
    }

    override fun getAlignment(): Float = 0f

    override fun getTooltipText(): String? {
        val snap = FinanceBridgeService.instance.snapshot()
        if (snap.mainThread == null) return null
        return buildString {
            append("<html>")
            append("<b>主线: ${snap.mainThread}</b><br>")
            append("phase: ${snap.threadPhase ?: "?"} · D${snap.threadAgeDays ?: "?"}<br>")
            if (snap.threadHealthSeries.isNotEmpty()) {
                append("健康度序列: ${snap.threadHealthSeries.joinToString(" → ")}<br>")
            }
            if (snap.currentLeader != null) {
                append("当前龙头: ${snap.currentLeader}<br>")
            }
            if (snap.liquidityEnv != null) {
                append("流动性: ${snap.liquidityEnv}<br>")
            }
            if (snap.canonicalDrifts.isNotEmpty()) {
                append("<br>⚠️ 主线命名漂移 ${snap.canonicalDrifts.size} 组")
            }
            append("</html>")
        }
    }

    override fun getClickConsumer(): Consumer<java.awt.event.MouseEvent>? = Consumer {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("Stocker")?.show(null)
    }

    private fun update() {
        SwingUtilities.invokeLater {
            statusBar?.updateWidget(ID)
        }
    }
}

/**
 * Factory registered in plugin.xml.
 */
class FinanceStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = FinanceStatusBarWidget.ID

    override fun getDisplayName(): String = "Stocker · Main Thread"

    override fun isAvailable(project: Project): Boolean =
        com.vermouthx.stocker.settings.StockerSetting.instance.financeBridgeEnabled

    override fun createWidget(project: Project): StatusBarWidget = FinanceStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
