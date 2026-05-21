package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceMarketSnapshot
import com.vermouthx.stocker.finance.FinanceState
import com.vermouthx.stocker.settings.StockerSetting
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer

/**
 * Two-line header above the quotes table:
 *   Row 1: active main_thread · phase · age days · health trend  (+ 🔄 if leader rotated)
 *   Row 2: market breadth — advancers/decliners, limit-up/down, turnover
 *
 * v2 enhancements:
 *   - 🚨 phase transition flash: when today's phase ≠ yesterday's, background flashes
 *     yellow for ~3 seconds the first time the header reloads with the new combo.
 *   - ↗/↘ health trend arrow derived from the last 3 days' thread_health_score samples.
 *   - 🔄 badge + tooltip when current_leader ≠ prior leader.
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

    /** Cached "last seen" combo so we only flash once per transition. */
    private var lastSeenPhase: String? = null
    private var lastSeenLeader: String? = null
    private var lastSeenThread: String? = null
    private val defaultBg: Color = JBColor.background()
    private val flashBg: Color = JBColor(Color(0xFFF1B8), Color(0x5A4B1A))   // soft amber

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
        val priorPhase = snap.priorThreadPhase
        val ageStr = snap.threadAgeDays?.let { "D$it" } ?: "D?"

        val phaseSegment = if (!priorPhase.isNullOrBlank() && priorPhase != phase) {
            // explicit transition arrow
            "$priorPhase → $phase ⚠️"
        } else {
            phase
        }
        val healthArrow = healthArrow(snap.threadHealthSeries)
        val rotationBadge = if (snap.leaderRotation) " 🔄" else ""
        val leftText = buildString {
            append("🧭 $mt · $phaseSegment · $ageStr")
            if (healthArrow.isNotEmpty()) append(" · $healthArrow")
            append(rotationBadge)
        }
        threadLeft.text = leftText
        if (snap.leaderRotation) {
            threadLeft.toolTipText = buildString {
                append("龙头轮换：${snap.priorLeader ?: "?"} → ${snap.currentLeader ?: "?"}")
            }
        } else {
            threadLeft.toolTipText = null
        }
        threadRight.text = snap.reportDate?.let { "@ $it" } ?: ""

        maybeFlashOnChange(mt, phase, snap.currentLeader)
    }

    private fun healthArrow(series: List<Int>): String {
        if (series.size < 2) {
            return series.firstOrNull()?.let { "健康度 $it" } ?: ""
        }
        val first = series.first()
        val last = series.last()
        val arrow = when {
            last > first + 2 -> "↗"
            last < first - 2 -> "↘"
            else -> "→"
        }
        return "健康度 $first $arrow $last"
    }

    /**
     * Flash background once when the (thread, phase, leader) triple flips.
     * Avoids repeated flashing during file-watcher noise — keyed on the *combo*.
     */
    private fun maybeFlashOnChange(thread: String, phase: String, leader: String?) {
        if (!StockerSetting.instance.financeHighlightThreadChange) return
        // First render after IDE start should not flash — we have no "previous" baseline yet.
        val isFirstSeen = lastSeenThread == null && lastSeenPhase == null && lastSeenLeader == null
        val changed = !isFirstSeen && (
            thread != lastSeenThread || phase != lastSeenPhase || leader != lastSeenLeader
        )
        lastSeenThread = thread
        lastSeenPhase = phase
        lastSeenLeader = leader
        if (changed) flashOnce()
    }

    private fun flashOnce() {
        threadRow.isOpaque = true
        threadRow.background = flashBg
        val timer = Timer(3_000) {
            threadRow.background = defaultBg
            threadRow.isOpaque = false
            repaint()
        }
        timer.isRepeats = false
        timer.start()
        repaint()
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
