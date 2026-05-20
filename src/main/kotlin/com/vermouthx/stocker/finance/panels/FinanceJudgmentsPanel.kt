package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceJudgmentsAggregator
import java.awt.BorderLayout
import java.time.LocalDate
import javax.swing.JPanel

/**
 * Reads ~/Claude/finance/judgments/YYYY-MM.jsonl and shows a 30-day rollup:
 *   - total judgment snapshots
 *   - per-agent breakdown
 *   - daily-review direction accuracy distribution
 *   - failure-signals hit ratio
 *   - last systematic-bias notes
 */
internal class FinanceJudgmentsPanel : JPanel(BorderLayout()) {

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
        val today = LocalDate.now()
        val stats = FinanceJudgmentsAggregator.aggregate(dir, days = 30, today = today)
        if (stats == null) {
            viewer.setEmptyMessage("找不到 ~/Claude/finance/judgments/。\n请先让 agents 落地若干报告以生成 judgments jsonl。")
            return
        }
        if (stats.totalJudgments == 0) {
            viewer.setEmptyMessage("最近 30 日尚无 judgment 记录。\n触发若干 agent 报告后会自动累积。")
            return
        }

        val md = buildString {
            appendLine("# 命中率与趋势  ·  最近 ${stats.days} 日")
            appendLine()
            appendLine("**总判断数**: ${stats.totalJudgments}")
            appendLine()
            appendLine("## 按 agent 分布")
            appendLine("| agent | 数量 |")
            appendLine("|---|---|")
            stats.byAgent.entries.sortedByDescending { it.value }.forEach { (a, n) ->
                appendLine("| $a | $n |")
            }

            if (stats.directionAccuracy.isNotEmpty()) {
                appendLine()
                appendLine("## daily-review 方向命中（direction_accuracy）")
                val total = stats.directionAccuracy.values.sum()
                stats.directionAccuracy.forEach { (k, v) ->
                    val pct = if (total > 0) "%.1f".format(100.0 * v / total) else "0.0"
                    appendLine("- $k : $v 次 ($pct%)")
                }
                val hits = (stats.directionAccuracy["hit"] ?: 0) + (stats.directionAccuracy["partial"] ?: 0) / 2.0
                if (total > 0) {
                    val pct = "%.1f".format(100.0 * hits / total)
                    appendLine()
                    appendLine("> 综合命中（hit + 0.5·partial）: **$pct%**")
                }
            }

            if (stats.failureSignalsTotal > 0) {
                val pct = "%.1f".format(100.0 * stats.failureSignalsHits / stats.failureSignalsTotal)
                appendLine()
                appendLine("## failure_signals 命中")
                appendLine("- 命中 ${stats.failureSignalsHits} / 总数 ${stats.failureSignalsTotal} = **$pct%**")
            }

            if (stats.systematicBiases.isNotEmpty()) {
                appendLine()
                appendLine("## 系统性偏差（最近）")
                stats.systematicBiases.take(8).forEach { appendLine("- $it") }
            }
        }
        viewer.setMarkdown(md)
    }
}
