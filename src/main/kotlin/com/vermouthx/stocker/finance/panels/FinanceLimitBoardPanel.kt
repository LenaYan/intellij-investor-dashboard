package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceReportLocator
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Renders the "涨停梯队 / Limit-up board" section from today's market-research.md.
 *
 * Falls back to anomaly-scanner.md if market-research is not yet generated.
 * If neither file is present, falls back to the most recent dated reports/&lt;date&gt;/.
 */
internal class FinanceLimitBoardPanel : JPanel(BorderLayout()) {

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
            viewer.setEmptyMessage("尚未发现 ~/Claude/finance/reports/<date>/ 报告目录。\n请先跑 market-research / anomaly-scanner agent 生成今日报告。")
            return
        }

        // Try anomaly-scanner first (purpose-built), then market-research.
        var section: String? = null
        var sourceTag: String? = null

        FinanceReportLocator.readReport(dir, "anomaly-scanner", date)?.let {
            section = FinanceReportLocator.extractSection(it, "涨停", "limit") ?: it
            sourceTag = "anomaly-scanner $date"
        }
        if (section == null) {
            FinanceReportLocator.readReport(dir, "market-research", date)?.let {
                section = FinanceReportLocator.extractSection(it, "涨停池", "涨停") ?: FinanceReportLocator.extractSection(it, "涨停")
                sourceTag = "market-research $date"
            }
        }
        if (section == null) {
            viewer.setEmptyMessage("$date 报告内未找到涨停池段落。\n可能：anomaly-scanner / market-research 当日未生成，或段落标题非「涨停池」。")
            return
        }
        viewer.setMarkdown("> 数据源: $sourceTag\n\n$section")
    }
}
