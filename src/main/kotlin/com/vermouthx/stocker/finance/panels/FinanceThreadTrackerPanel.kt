package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBSplitter
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceReportLocator
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Thread Tracker tab.
 *
 * v3 layout (split vertically):
 *   - Top   : [FinanceScenarioPanel] — live branch state machine driven by today's
 *             `thread_scenario_tree` YAML + the leader's live quote.
 *   - Bottom: markdown viewer for today's thread-tracker.md (the long-form historical
 *             narrative; supplements the state machine but is no longer the primary view).
 *
 * Splitter ratio defaults to 0.65 (panel-heavy) and is user-resizable. If neither
 * payload is available the panel shows the markdown viewer's empty state.
 */
internal class FinanceThreadTrackerPanel : JPanel(BorderLayout()) {

    private val scenarioPanel = FinanceScenarioPanel()
    private val markdownViewer = FinanceMarkdownViewer()
    private val refreshHook: () -> Unit = { reloadMarkdown() }

    init {
        val split = JBSplitter(true, 0.65f).apply {
            firstComponent = scenarioPanel
            secondComponent = markdownViewer
            dividerWidth = 3
        }
        add(split, BorderLayout.CENTER)
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reloadMarkdown()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
        scenarioPanel.dispose()
    }

    private fun reloadMarkdown() {
        val dir = FinanceBridgeService.instance.financeDir()
        val date = FinanceReportLocator.mostRecentReportDate(dir) ?: run {
            markdownViewer.setEmptyMessage("暂无 thread-tracker 报告。\n跑 `/thread-tracker` 后会自动刷新。")
            return
        }
        val md = FinanceReportLocator.readReport(dir, "thread-tracker", date)
        if (md == null) {
            markdownViewer.setEmptyMessage("$date 未生成 thread-tracker.md。\n可尝试运行 thread-tracker agent。")
            return
        }
        markdownViewer.setMarkdown(md)
    }
}
