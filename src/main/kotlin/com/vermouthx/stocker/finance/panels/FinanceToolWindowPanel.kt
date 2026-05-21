package com.vermouthx.stocker.finance.panels

import com.intellij.openapi.Disposable
import com.intellij.ui.components.JBTabbedPane
import com.vermouthx.stocker.settings.StockerSetting
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Container shown as the new "Finance" tab inside Stocker's tool window.
 *
 * Sub-tabs (some are toggleable via settings):
 *   📰 Reports browser   – any markdown report under reports/&lt;today&gt;/
 *   📈 Limit-up board    – today's 涨停梯队 section
 *   💰 Sector flow       – today's 板块资金流 section
 *   🧭 Threads           – thread-tracker.md
 *   🎯 命中率             – 30-day judgments rollup
 *   🎯 买入点 (v2)        – entry-timing.md (if enabled)
 *   📋 昨日对照 (v2)      – daily-review calibration (if enabled)
 */
internal class FinanceToolWindowPanel : JPanel(BorderLayout()), Disposable {

    private val reportsPanel = FinanceReportsPanel()
    private val limitBoardPanel = FinanceLimitBoardPanel()
    private val sectorFlowPanel = FinanceSectorFlowPanel()
    private val threadPanel = FinanceThreadTrackerPanel()
    private val judgmentsPanel = FinanceJudgmentsPanel()
    private val entryTimingPanel: FinanceEntryTimingPanel?
    private val calibrationPanel: FinanceCalibrationPanel?

    init {
        val setting = StockerSetting.instance
        entryTimingPanel = if (setting.financeShowEntryTimingTab) FinanceEntryTimingPanel() else null
        calibrationPanel = if (setting.financeShowCalibrationTab) FinanceCalibrationPanel() else null

        val tabs = JBTabbedPane()
        tabs.addTab("📰 报告速读", reportsPanel)
        tabs.addTab("📈 涨停梯队", limitBoardPanel)
        tabs.addTab("💰 板块资金", sectorFlowPanel)
        tabs.addTab("🧭 主线追踪", threadPanel)
        entryTimingPanel?.let { tabs.addTab("🎯 买入点", it) }
        calibrationPanel?.let { tabs.addTab("📋 昨日对照", it) }
        tabs.addTab("📊 命中率", judgmentsPanel)
        add(tabs, BorderLayout.CENTER)
    }

    val component: JComponent get() = this

    override fun dispose() {
        reportsPanel.dispose()
        limitBoardPanel.dispose()
        sectorFlowPanel.dispose()
        threadPanel.dispose()
        judgmentsPanel.dispose()
        entryTimingPanel?.dispose()
        calibrationPanel?.dispose()
    }
}
