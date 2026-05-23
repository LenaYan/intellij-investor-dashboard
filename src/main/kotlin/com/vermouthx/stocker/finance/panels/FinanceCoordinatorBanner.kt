package com.vermouthx.stocker.finance.panels

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.vermouthx.stocker.finance.CoordinatorSchedule
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceDailyCoordinatorLoader
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Pinned banner at the top of the Finance tool window that summarises today's
 * daily-coordinator.md schedule:
 *
 *   📅 2026-05-23 (DOW=6) · 非交易日 · 持仓 0 · 主线 9         [✅ 1 · ⏳ 0 · ⏭️ 3]
 *   📌 2026-05-28 macro 高: 美国 Q1 GDP 修正值 + 初请失业金
 *
 * - Background tint:
 *     · 全部 ✅ 已完成 → 绿
 *     · 有 ⏳ pending → 黄
 *     · 全 ⏭️ skipped 或没数据 → 灰
 *
 * Reloads whenever the file watcher fires.
 */
internal class FinanceCoordinatorBanner : JPanel(BorderLayout()) {

    private val titleLine = JBLabel("", SwingConstants.LEFT)
    private val statusBadge = JBLabel("", SwingConstants.RIGHT)
    private val catalystLine = JBLabel("", SwingConstants.LEFT)

    private val topRow = JPanel(BorderLayout()).apply { isOpaque = false }

    private val refreshHook: () -> Unit = { reload() }

    init {
        layout = BorderLayout()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        )

        titleLine.font = titleLine.font.deriveFont(Font.BOLD)
        statusBadge.font = statusBadge.font.deriveFont(Font.BOLD)
        catalystLine.font = catalystLine.font.deriveFont(Font.PLAIN, catalystLine.font.size - 1f)
        catalystLine.foreground = JBColor.GRAY

        topRow.add(titleLine, BorderLayout.WEST)
        topRow.add(statusBadge, BorderLayout.EAST)

        add(topRow, BorderLayout.NORTH)
        add(catalystLine, BorderLayout.SOUTH)

        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        reload()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
    }

    private fun reload() {
        val dir = FinanceBridgeService.instance.financeDir()
        val s = FinanceDailyCoordinatorLoader.load(dir)
        if (s == null) {
            isVisible = false
            return
        }
        isVisible = true
        render(s)
    }

    private fun render(s: CoordinatorSchedule) {
        val dow = s.dow?.let { " (DOW=$it)" } ?: ""
        val tradingFlag = when (s.isTradingDay) {
            true -> "交易日"
            false -> "非交易日"
            null -> "?"
        }
        val context = buildString {
            append("📅 ${s.date}$dow · $tradingFlag")
            s.holdingsCount?.let { append(" · 持仓 $it") }
            s.watchlistThreads?.let { append(" · 主线 $it") }
        }
        titleLine.text = context

        val total = s.agentStatus.size
        val statusText = if (total == 0) {
            "无调度项"
        } else {
            "✅ ${s.doneCount} · ⏳ ${s.pendingCount} · ⏭️ ${s.skippedCount}"
        }
        statusBadge.text = statusText
        statusBadge.toolTipText = buildTooltip(s)

        // Catalyst footer (first 1 line)
        if (s.upcomingCatalysts.isNotEmpty()) {
            catalystLine.text = "📌 " + s.upcomingCatalysts.first()
            catalystLine.isVisible = true
        } else {
            catalystLine.isVisible = false
        }

        // Background tint
        val bg = when {
            s.pendingCount > 0 -> JBColor(Color(0xFFF1B8), Color(0x4A3D14))  // amber
            s.doneCount > 0 && s.pendingCount == 0 -> JBColor(Color(0xD7F0D7), Color(0x1F3A1F))  // green
            else -> JBColor.background()
        }
        background = bg
        isOpaque = true
        repaint()
    }

    private fun buildTooltip(s: CoordinatorSchedule): String = buildString {
        append("<html>")
        append("<b>${s.date} 调度详情</b><br>")
        s.agentStatus.groupBy { it.groupName }.forEach { (group, rows) ->
            append("<br><i>$group</i><br>")
            rows.forEach { r ->
                append("&nbsp;&nbsp;${r.statusEmoji} ${r.agent}")
                if (!r.note.isNullOrBlank()) append(" — ${r.note}")
                append("<br>")
            }
        }
        if (s.upcomingCatalysts.size > 1) {
            append("<br><i>即将催化</i><br>")
            s.upcomingCatalysts.forEach { append("&nbsp;&nbsp;• $it<br>") }
        }
        append("</html>")
    }
}
