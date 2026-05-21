package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.EntryTimingRecommendation
import com.vermouthx.stocker.finance.FinanceBridgeService
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Renders the most recent entry-timing.md recommendation list as a markdown summary table.
 *
 *  | 标的 | grade | 买入类型 | 触发价 | 失效价 | 共振 | 主线 | 首仓 | 加仓 |
 *  ...
 *  ## 触发条件
 *  ## 失效条件
 *  ## 4 周回溯（如有）
 */
internal class FinanceEntryTimingPanel : JPanel(BorderLayout()) {

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
        val recs = FinanceBridgeService.instance.snapshot().entryTimingAll
        if (recs.isEmpty()) {
            viewer.setEmptyMessage(
                "找不到 entry-timing.md。\n" +
                    "在 Claude 终端运行 /entry 或 candidate-ranker → entry-timing 链路生成。\n" +
                    "（位置: ~/Claude/finance/reports/<today>/entry-timing.md）"
            )
            return
        }

        val md = buildString {
            appendLine("# 买入点识别 · entry-timing")
            appendLine()
            appendLine("> 来源: ~/Claude/finance/reports/<today>/entry-timing.md")
            appendLine("> 共 ${recs.size} 只候选 — 评级 ≥ B 时盘中价格穿越触发/失效会推送通知")
            appendLine()
            appendLine("## 推荐汇总")
            appendLine("| 标的 | grade | 类型 | 触发价 | 失效价 | 共振 | 位置 | 主线 | 首仓 | 加仓 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            recs.forEach { r ->
                appendLine(rowFor(r))
            }

            val actionable = recs.filter { it.grade?.trim() in setOf("A+", "A", "B") }
            if (actionable.isNotEmpty()) {
                appendLine()
                appendLine("## 触发条件（grade ≥ B）")
                actionable.forEach { r ->
                    appendLine()
                    appendLine("### ${r.gradeGlyph ?: r.grade ?: "?"}  ${r.name ?: r.symbol} (${r.symbol})")
                    if (r.triggers.isEmpty()) {
                        appendLine("- （无触发条件）")
                    } else {
                        r.triggers.forEach { appendLine("- $it") }
                    }
                }

                appendLine()
                appendLine("## 失效条件（grade ≥ B）")
                actionable.forEach { r ->
                    appendLine()
                    appendLine("### ${r.name ?: r.symbol} (${r.symbol})")
                    if (r.invalidations.isEmpty()) {
                        appendLine("- （无失效条件）")
                    } else {
                        r.invalidations.forEach { appendLine("- $it") }
                    }
                }
            }
        }
        viewer.setMarkdown(md)
    }

    private fun rowFor(r: EntryTimingRecommendation): String {
        fun fmtPx(d: Double?) = d?.let { "¥%.2f".format(it) } ?: "—"
        val name = (r.name ?: "—") + " (${r.symbol})"
        val typ = r.entryType ?: "—"
        val resonance = r.resonanceScore?.toString() ?: "—"
        val pos = r.positionScore?.toString() ?: "—"
        val thread = r.alignedThread?.let { "$it · ${r.threadPhase ?: "?"}" } ?: "—"
        val first = r.firstPositionPct?.let { "$it%" } ?: "—"
        val add = r.addSchedule ?: "—"
        return "| $name | ${r.gradeGlyph ?: r.grade ?: "?"} | $typ | ${fmtPx(r.triggerPrice)} | ${fmtPx(r.invalidationPrice)} | $resonance | $pos | $thread | $first | $add |"
    }
}
