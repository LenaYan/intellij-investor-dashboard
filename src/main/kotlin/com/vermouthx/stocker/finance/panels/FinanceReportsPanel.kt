package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceReportLocator
import java.awt.BorderLayout
import java.time.LocalDate
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Reports browser: left list of today's available reports, right markdown view of the selected one.
 * Empty state prompts the user with the expected directory layout.
 */
internal class FinanceReportsPanel : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<String>()
    private val list = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ReportCellRenderer()
        emptyText.text = "今日无报告"
    }
    private val viewer = FinanceMarkdownViewer()
    private var currentDate: LocalDate? = null
    private val refreshHook: () -> Unit = { reloadList() }

    init {
        val splitter = JBSplitter(false, 0.28f).apply {
            firstComponent = JBScrollPane(list)
            secondComponent = viewer
        }
        add(splitter, BorderLayout.CENTER)

        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val agent = list.selectedValue ?: return@addListSelectionListener
            renderSelected(agent)
        }
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reloadList()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
    }

    private fun reloadList() {
        val dir = FinanceBridgeService.instance.financeDir()
        val date = FinanceReportLocator.mostRecentReportDate(dir)
        if (date == null) {
            currentDate = null
            listModel.clear()
            viewer.setEmptyMessage(
                "暂无 reports/<date>/ 报告。\n\n" +
                "期望目录: ${dir}/reports/${LocalDate.now()}/\n" +
                "示例: overnight-brief.md / market-research.md / daily-review.md"
            )
            return
        }
        val reports = FinanceReportLocator.availableTodayReports(dir, date)
        val prevSelection = list.selectedValue
        currentDate = date
        listModel.clear()
        reports.forEach { listModel.addElement(it) }
        if (reports.isNotEmpty()) {
            val idx = (prevSelection?.let { reports.indexOf(it) } ?: -1).takeIf { it >= 0 } ?: 0
            list.selectedIndex = idx
            renderSelected(listModel.getElementAt(idx))
        } else {
            viewer.setEmptyMessage("$date 当日无报告文件。")
        }
    }

    private fun renderSelected(agent: String) {
        val dir = FinanceBridgeService.instance.financeDir()
        val date = currentDate ?: return
        val md = FinanceReportLocator.readReport(dir, agent, date)
        if (md == null) {
            viewer.setEmptyMessage("无法读取 $agent.md")
        } else {
            viewer.setMarkdown(md)
        }
    }

    private class ReportCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): java.awt.Component {
            val c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JComponent
            border = javax.swing.BorderFactory.createEmptyBorder(6, 12, 6, 12)
            return c
        }
    }
}
