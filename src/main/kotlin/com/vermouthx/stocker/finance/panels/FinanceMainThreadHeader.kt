package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceMarketSnapshot
import com.vermouthx.stocker.finance.FinanceState
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Two-line header above the quotes table:
 *   Row 1: active main_thread · phase · age days · report date
 *   Row 2: market breadth — advancers/decliners, limit-up/down, turnover
 *
 * Auto-hides whichever line lacks data, so users without finance/ see nothing.
 */
internal class FinanceMainThreadHeader : JPanel(BorderLayout()) {

    private val threadLeft = JBLabel("", SwingConstants.LEFT)
    private val threadRight = JBLabel("", SwingConstants.RIGHT)
    private val threadRow = JPanel(BorderLayout())

    private val breadthLeft = JBLabel("", SwingConstants.LEFT)
    private val breadthRight = JBLabel("", SwingConstants.RIGHT)
    private val breadthRow = JPanel(BorderLayout())

    private val refreshHook: () -> Unit = { reload() }

    init {
        layout = GridLayout(2, 1)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)
        )

        val emphasis = threadLeft.font.deriveFont(Font.BOLD)
        threadLeft.font = emphasis
        threadRow.add(threadLeft, BorderLayout.WEST)
        threadRow.add(threadRight, BorderLayout.EAST)
        threadRow.isOpaque = false

        breadthLeft.font = breadthLeft.font.deriveFont(Font.PLAIN, breadthLeft.font.size - 1f)
        breadthRight.font = breadthRight.font.deriveFont(Font.PLAIN, breadthRight.font.size - 1f)
        breadthRow.add(breadthLeft, BorderLayout.WEST)
        breadthRow.add(breadthRight, BorderLayout.EAST)
        breadthRow.isOpaque = false

        add(threadRow)
        add(breadthRow)

        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reload()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
    }

    private fun reload() {
        val snap: FinanceState.Snapshot = FinanceBridgeService.instance.snapshot()
        renderThreadRow(snap)
        renderBreadthRow(snap.marketSnapshot)

        val hasContent = threadRow.isVisible || breadthRow.isVisible
        isVisible = hasContent
        repaint()
    }

    private fun renderThreadRow(snap: FinanceState.Snapshot) {
        val mt = snap.mainThread
        if (mt.isNullOrBlank()) {
            threadRow.isVisible = false
            return
        }
        threadRow.isVisible = true
        val phase = snap.threadPhase ?: "?"
        val ageStr = snap.threadAgeDays?.let { "D$it" } ?: "D?"
        threadLeft.text = "🧭 $mt · $phase · $ageStr"
        threadRight.text = snap.reportDate?.let { "@ $it" } ?: ""
    }

    private fun renderBreadthRow(s: FinanceMarketSnapshot?) {
        if (s == null) {
            breadthRow.isVisible = false
            return
        }
        val parts = ArrayList<String>()
        if (s.advancers != null && s.decliners != null) parts.add("涨跌 ${s.advancers}:${s.decliners}")
        if (s.limitUp != null && s.limitDown != null) parts.add("涨停/跌停 ${s.limitUp}/${s.limitDown}")
        if (s.turnoverYi != null) parts.add("成交 ${"%.0f".format(s.turnoverYi)}亿")
        if (s.northboundYi != null) parts.add("北向 ${"%+.1f".format(s.northboundYi)}亿")
        if (parts.isEmpty()) {
            breadthRow.isVisible = false
            return
        }
        breadthRow.isVisible = true
        breadthLeft.text = "📊 " + parts.joinToString("  ·  ")
        breadthRight.text = "(${s.date})"
    }
}
