package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.CalibrationItem
import com.vermouthx.stocker.finance.CalibrationSummary
import com.vermouthx.stocker.finance.FinanceBridgeService
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 「昨日预案 vs 今日实际」对照面板。
 *
 * 数据来源: ~/Claude/finance/reports/&lt;today&gt;/daily-review.md 的 YAML 块里
 *   - calibration.failure_signals_results
 *   - calibration.catalysts_due_today
 *   - calibration.thread_continuity (phase_evolution / health_trend / naming_drift / rotation)
 *   - direction_accuracy
 *
 * 渲染为 markdown 表格 + 分类小节，便于盘中复看「昨天为什么这么判断，今天打脸了没」。
 */
internal class FinanceCalibrationPanel : JPanel(BorderLayout()) {

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
        val snap = FinanceBridgeService.instance.snapshot()
        val items = snap.calibrationItems
        val summary = snap.calibrationSummary
        if (items.isEmpty() || summary == null) {
            viewer.setEmptyMessage(
                "尚未读到 daily-review.md 校准块。\n" +
                    "盘后由 daily-review agent 自动生成（位置: ~/Claude/finance/reports/<today>/daily-review.md）。\n" +
                    "早盘开盘前会读到昨日的版本作为参考。"
            )
            return
        }

        val md = buildString {
            appendLine("# 昨日预案 vs 今日实际")
            appendLine()
            appendLine("> daily-review 校准: ${summary.reviewedDate ?: "?"}")
            if (summary.reviewedReports.isNotEmpty()) {
                appendLine("> 被复盘的报告: " + summary.reviewedReports.joinToString(", "))
            }
            appendLine()
            renderSummary(summary)

            appendLine()
            appendLine("## 逐条对照")
            appendLine("| # | 类型 | 来源 | 预案 | 实际 | 备注 |")
            appendLine("|---|---|---|---|---|---|")
            items.forEachIndexed { idx, it ->
                appendLine("| ${idx + 1} | ${kindLabel(it.kind)} | ${it.source} | ${escape(it.text)} | ${outcomeLabel(it.outcome)} | ${it.detail ?: "—"} |")
            }

            val misses = items.filter { it.outcome == CalibrationItem.Outcome.MISS || it.outcome == CalibrationItem.Outcome.REVERSE }
            if (misses.isNotEmpty()) {
                appendLine()
                appendLine("## ❌ 偏离项细节（建议重读 daily-review.md 找根因）")
                misses.forEach {
                    appendLine()
                    appendLine("### ${outcomeLabel(it.outcome)}  ${kindLabel(it.kind)}")
                    appendLine("- 预案: ${it.text}")
                    if (it.detail != null) appendLine("- 备注: ${it.detail}")
                    appendLine("- 来源: ${it.source}@${it.sourceDate ?: "?"}")
                }
            }

            if (summary.systematicBias.isNotEmpty()) {
                appendLine()
                appendLine("## 系统性偏差（daily-review 自识别）")
                summary.systematicBias.take(8).forEach { appendLine("- $it") }
            }
        }
        viewer.setMarkdown(md)
    }

    private fun renderSummary(s: CalibrationSummary): String = buildString {
        appendLine("## 汇总")
        appendLine()
        appendLine("| 指标 | 值 |")
        appendLine("|---|---|")
        appendLine("| 总条目 | ${s.totalItems} |")
        appendLine("| ✅ 命中 (含部分) | ${s.hit} |")
        appendLine("| ❌ 未命中 | ${s.miss} |")
        appendLine("| ⚠️ 反向 | ${s.reverse} |")
        appendLine("| ⏸ 无法判断 | ${s.notEvaluable} |")
        appendLine("| 昨日方向 | ${outcomeLabel(s.direction)} |")
        val denom = (s.hit + s.miss + s.reverse).coerceAtLeast(1)
        val pct = "%.1f".format(100.0 * s.hit / denom)
        appendLine("| 综合命中率 (hit / hit+miss+reverse) | **$pct%** |")
    }

    private fun kindLabel(k: CalibrationItem.Kind): String = when (k) {
        CalibrationItem.Kind.DIRECTION -> "方向"
        CalibrationItem.Kind.FAILURE_SIGNAL -> "证伪信号"
        CalibrationItem.Kind.CATALYST -> "催化事件"
        CalibrationItem.Kind.THREAD_PHASE -> "主线 phase"
        CalibrationItem.Kind.THREAD_HEALTH -> "主线 health"
        CalibrationItem.Kind.NAMING_DRIFT -> "命名漂移"
        CalibrationItem.Kind.ROTATION -> "龙头轮换"
    }

    private fun outcomeLabel(o: CalibrationItem.Outcome): String = when (o) {
        CalibrationItem.Outcome.HIT -> "✅ 命中"
        CalibrationItem.Outcome.PARTIAL -> "✳️ 部分命中"
        CalibrationItem.Outcome.MISS -> "❌ 未命中"
        CalibrationItem.Outcome.REVERSE -> "⚠️ 反向"
        CalibrationItem.Outcome.NOT_EVALUABLE -> "⏸ 无法判断"
        CalibrationItem.Outcome.UNKNOWN -> "—"
    }

    private fun escape(s: String): String = s.replace("|", "\\|")
}
