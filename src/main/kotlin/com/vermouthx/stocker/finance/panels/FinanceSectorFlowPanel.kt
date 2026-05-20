package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceReportLocator
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Renders the "板块资金流 / Sector flow" section from today's market-research.md
 * (with flow-monitor.md as a secondary source for theme-specific reports).
 */
internal class FinanceSectorFlowPanel : JPanel(BorderLayout()) {

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
            viewer.setEmptyMessage("暂无 reports/<date>/ 报告。\n生成 market-research 或 flow-monitor 后会自动刷新。")
            return
        }

        var section: String? = null
        var tag: String? = null

        FinanceReportLocator.readReport(dir, "market-research", date)?.let {
            section = FinanceReportLocator.extractSection(it, "板块资金流", "板块", "sector flow", "行业")
            tag = "market-research $date"
        }
        if (section == null) {
            FinanceReportLocator.readReport(dir, "flow-monitor", date)?.let {
                section = FinanceReportLocator.extractSection(it, "板块", "主力资金", "资金净流入") ?: it
                tag = "flow-monitor $date"
            }
        }
        if (section == null) {
            viewer.setEmptyMessage("$date 报告内未找到板块资金流段落。")
            return
        }
        viewer.setMarkdown("> 数据源: $tag\n\n$section")
    }
}
