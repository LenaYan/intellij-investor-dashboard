package com.vermouthx.stocker.finance.panels

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Container shown as the new "Finance" tab inside Stocker's tool window.
 * Holds four sub-tabs:
 *   📊 Reports browser  – any markdown report under reports/&lt;today&gt;/
 *   📈 Limit-up board   – today's 涨停梯队 section
 *   💰 Sector flow      – today's 板块资金流 section
 *   🧭 Threads          – main_thread evolution (kept lightweight: full thread-tracker.md)
 */
internal class FinanceToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val reportsPanel = FinanceReportsPanel()
    private val limitBoardPanel = FinanceLimitBoardPanel()
    private val sectorFlowPanel = FinanceSectorFlowPanel()
    private val threadPanel = FinanceThreadTrackerPanel()
    private val judgmentsPanel = FinanceJudgmentsPanel()

    init {
        val tabs = JBTabbedPane()
        tabs.addTab("📰 报告速读", reportsPanel)
        tabs.addTab("📈 涨停梯队", limitBoardPanel)
        tabs.addTab("💰 板块资金", sectorFlowPanel)
        tabs.addTab("🧭 主线追踪", threadPanel)
        tabs.addTab("🎯 命中率", judgmentsPanel)
        add(tabs, BorderLayout.CENTER)
    }

    val component: JComponent get() = this

    override fun dispose() {
        reportsPanel.dispose()
        limitBoardPanel.dispose()
        sectorFlowPanel.dispose()
        threadPanel.dispose()
        judgmentsPanel.dispose()
    }
}
