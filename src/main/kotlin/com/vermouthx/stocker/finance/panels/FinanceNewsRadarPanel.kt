package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceNewsRadarLoader
import com.vermouthx.stocker.finance.NewsRadarReport
import com.vermouthx.stocker.finance.NewsRadarEvent
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 📢 消息雷达 Tab — renders today's news-radar.md (v2.4) YAML snapshot as a
 * markdown summary:
 *
 *   1. Step 0.5 价量-消息对账段（unexplained_gap 显眼提示）
 *   2. 高置信消息（matrix_quadrant 含"纳入主线推理"）
 *   3. 政策推演（policy_deductions）
 *   4. 中低置信小作文台账（rumor_ledger_today，只显示今日新增；完整台账见 🗞️ Tab）
 *
 * Falls back to "暂无 news-radar.md" hint when no report is present in the
 * last 5 days.
 */
internal class FinanceNewsRadarPanel : JPanel(BorderLayout()) {

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
        val reports = FinanceNewsRadarLoader.loadFromReports(FinanceBridgeService.instance.financeDir())
        if (reports.isEmpty()) {
            viewer.setEmptyMessage(
                "找不到 news-radar.md。\n\n" +
                    "新闻面雷达由 news-radar agent 在盘前 / 盘后各跑一次产出。\n" +
                    "位置: ~/Claude/finance/reports/<today>/news-radar.md\n" +
                    "（变体 news-radar-thematic.md 也会显示）"
            )
            return
        }
        viewer.setMarkdown(buildMarkdown(reports))
    }

    private fun buildMarkdown(reports: List<NewsRadarReport>): String = buildString {
        appendLine("# 消息面雷达 · news-radar")
        appendLine()
        appendLine("> ${reports.size} 份报告 (${reports.first().date}, 含 ${reports.joinToString { it.sourceBasename }})")
        appendLine()

        // ── Step 0.5 价量对账（若多份，取第一个有数据的）─────────────────────
        val firstRecon = reports.firstNotNullOfOrNull { it.reconciliation }
        if (firstRecon != null) {
            appendLine("## 0. 价量-消息对账 (Step 0.5)")
            appendLine()
            val idx = firstRecon.index ?: "指数"
            val pct = firstRecon.changePct?.let { "%+.2f%%".format(it) } ?: "?"
            appendLine("- **$idx**: $pct")
            if (firstRecon.explainedBy.isNotEmpty()) {
                appendLine("- 解释来源:")
                firstRecon.explainedBy.forEach { appendLine("    - $it") }
            }
            if (firstRecon.unexplainedGap != null) {
                appendLine()
                appendLine("- 🚨 **未解释的消息缺口**: ${firstRecon.unexplainedGap}")
                appendLine("    > 需追查催化（agent 可能漏抓）")
            } else {
                appendLine("- ✅ 无未解释的消息缺口")
            }
            appendLine()
        }

        // ── 高置信消息表 ───────────────────────────────────────────────
        val allHigh = reports.flatMap { it.highConfidenceEvents }
        if (allHigh.isNotEmpty()) {
            appendLine("## 1. 高置信消息（已纳入主线推理）")
            appendLine()
            appendLine("| 消息 | 类别 | 置信 | 信源 | freshness | 影响 | 方向 | 关联主线 | 矩阵格 |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            allHigh.sortedByDescending { it.confidenceScore ?: 0 }.forEach { e ->
                appendLine(rowFor(e))
            }
            appendLine()
        }

        // ── 政策推演 ───────────────────────────────────────────────────
        val allPolicies = reports.flatMap { it.policyDeductions }
        if (allPolicies.isNotEmpty()) {
            appendLine("## 2. 政策推演 (policy_deductions)")
            appendLine()
            allPolicies.forEach { p ->
                val conf = p.confidence?.let { " (置信 $it)" } ?: ""
                appendLine("### ${p.policy}$conf")
                if (!p.purpose.isNullOrBlank()) appendLine("- **目的**: ${p.purpose}")
                if (p.followUpPath.isNotEmpty()) {
                    appendLine("- **后续路径**:")
                    p.followUpPath.forEach { appendLine("    - $it") }
                }
                appendLine()
            }
        }

        // ── 中低置信小作文（节选；完整去 🗞️ Tab）─────────────────────────
        val allRumors = reports.flatMap { it.rumorLedgerToday }
        if (allRumors.isNotEmpty()) {
            appendLine("## 3. 今日新增小作文（节选 — 完整去 🗞️ 小作文台账）")
            appendLine()
            appendLine("| 消息 | 类别 | 置信 | 影响 | 矩阵格 |")
            appendLine("|---|---|---|---|---|")
            allRumors.sortedByDescending { (it.impactLevel ?: "").length }.take(10).forEach { e ->
                val conf = e.confidenceScore?.toString() ?: "—"
                val impact = e.impactLevel ?: "—"
                val q = e.matrixQuadrant ?: "—"
                appendLine("| ${e.headline.take(50)} | ${e.category ?: "—"} | $conf | $impact | $q |")
            }
            appendLine()
        }
    }

    private fun rowFor(e: NewsRadarEvent): String {
        val headline = e.headline.replace("|", "\\|").take(60)
        val conf = e.confidenceScore?.toString() ?: "—"
        val src = e.sourceTier ?: "—"
        val fresh = (e.freshness ?: "—").replace("|", "\\|")
        val impact = e.impactLevel ?: "—"
        val dir = directionGlyph(e.direction)
        val thread = e.relatedThread?.takeIf { it.isNotBlank() } ?: "—"
        val quad = (e.matrixQuadrant ?: "—").replace("|", "\\|")
        return "| $headline | ${e.category ?: "—"} | $conf | $src | $fresh | $impact | $dir | $thread | $quad |"
    }

    private fun directionGlyph(d: String?): String = when (d) {
        "利多" -> "🟢 利多"
        "利空" -> "🔴 利空"
        "中性" -> "⚪ 中性"
        else -> d ?: "—"
    }
}
