package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceReportLocator
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Renders today's thread-tracker.md report.
 */
internal class FinanceThreadTrackerPanel : JPanel(BorderLayout()) {

    private val viewer = FinanceMarkdownViewer()
    private val refreshHook: () -> Unit = { reload() }

    init {
        add(viewer, BorderLayout.CENTER)
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reload()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
    }

    private fun reload() {
        val dir = FinanceBridgeService.instance.financeDir()
        val date = FinanceReportLocator.mostRecentReportDate(dir) ?: run {
            viewer.setEmptyMessage("暂无 thread-tracker 报告。\n跑 `/thread-tracker` 后会自动刷新。")
            return
        }
        val md = FinanceReportLocator.readReport(dir, "thread-tracker", date)
        if (md == null) {
            viewer.setEmptyMessage("$date 未生成 thread-tracker.md。\n可尝试运行 thread-tracker agent。")
            return
        }
        viewer.setMarkdown(md)
    }
}
