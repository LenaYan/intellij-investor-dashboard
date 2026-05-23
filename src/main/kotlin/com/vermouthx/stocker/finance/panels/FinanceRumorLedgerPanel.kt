package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceRumorLedgerLoader
import com.vermouthx.stocker.finance.RumorEntry
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 🗞️ 小作文台账 Tab — renders judgments/rumors.jsonl entries grouped by status,
 * with scope-hit highlighting (when the rumor's scope mentions a symbol from
 * the user's watchlist or portfolio, the row gets a 🎯 prefix).
 *
 * Grouping:
 *   - pending / watching → 顶部（待证伪/证实）
 *   - confirmed         → 中部（已证实）
 *   - refuted           → 底部（已证伪 — 留档复盘）
 */
internal class FinanceRumorLedgerPanel : JPanel(BorderLayout()) {

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
        val rumors = FinanceRumorLedgerLoader.load(FinanceBridgeService.instance.financeDir())
        if (rumors.isEmpty()) {
            viewer.setEmptyMessage(
                "找不到 judgments/rumors.jsonl 或台账为空。\n\n" +
                    "小作文台账由 news-radar agent 维护（v2.4），收录中低置信但可能高影响的传闻。\n" +
                    "位置: ~/Claude/finance/judgments/rumors.jsonl"
            )
            return
        }
        viewer.setMarkdown(buildMarkdown(rumors))
    }

    private fun buildMarkdown(rumors: List<RumorEntry>): String = buildString {
        val watchSymbols = FinanceBridgeService.instance.snapshot().watchlistBySymbol.values
        val portfolioSymbols = FinanceBridgeService.instance.snapshot().portfolioBySymbol.values
        val watchKeywords = (watchSymbols.mapNotNull { it.name } + watchSymbols.map { it.symbol } +
            watchSymbols.mapNotNull { it.sector } +
            portfolioSymbols.mapNotNull { it.name } + portfolioSymbols.map { it.symbol }).distinct()
            .filter { it.isNotBlank() && it.length >= 2 }

        appendLine("# 小作文台账 · rumors.jsonl")
        appendLine()
        appendLine("> 共 ${rumors.size} 条 · 来源: ~/Claude/finance/judgments/rumors.jsonl")
        appendLine("> 🎯 = scope 命中 watchlist / 持仓")
        appendLine()

        val byStatus = rumors.groupBy { it.status }
        val pendingOrder = listOf("pending", "watching", "confirmed", "refuted")
        for (st in pendingOrder + (byStatus.keys - pendingOrder.toSet())) {
            val list = byStatus[st] ?: continue
            appendLine("## ${statusHeader(st)}  (${list.size})")
            appendLine()
            appendLine("| | 消息 | 类别 | 置信 | 影响 | 矩阵格 | 方向/量级 | 时间窗 | scope | 首见 |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            list.sortedByDescending { it.lastUpdated ?: it.firstSeen ?: "" }.forEach { r ->
                val hit = if (r.scope != null && watchKeywords.any { kw -> r.scope.contains(kw) }) "🎯" else ""
                appendLine(rowFor(r, hit))
            }
            appendLine()

            // confirmed/refuted: 列出已实现的市场影响以便复盘
            if (st in setOf("confirmed", "refuted") && list.isNotEmpty()) {
                val withResolution = list.filter { !it.resolutionNote.isNullOrBlank() || !it.actualMarketImpact.isNullOrBlank() }
                if (withResolution.isNotEmpty()) {
                    appendLine("### ${statusHeader(st)} · 复盘记录")
                    appendLine()
                    withResolution.forEach { r ->
                        appendLine("- **${r.headline.take(60)}**")
                        if (!r.resolutionNote.isNullOrBlank()) appendLine("    - 解决: ${r.resolutionNote}")
                        if (!r.actualMarketImpact.isNullOrBlank()) appendLine("    - 实际影响: ${r.actualMarketImpact}")
                        if (!r.resolutionDate.isNullOrBlank()) appendLine("    - 日期: ${r.resolutionDate}")
                    }
                    appendLine()
                }
            }
        }
    }

    private fun rowFor(r: RumorEntry, hit: String): String {
        val headline = r.headline.replace("|", "\\|").take(55)
        val conf = "${r.confidenceScore ?: "—"} (${r.confidenceTier ?: "?"})"
        val impact = r.impactLevel ?: "—"
        val quad = (r.quadrant ?: "—").replace("|", "\\|")
        val dirMag = listOfNotNull(r.direction, r.magnitude).joinToString("/").ifBlank { "—" }
        val horizon = r.horizon ?: "—"
        val scope = (r.scope ?: "—").replace("|", "\\|").take(40)
        val firstSeen = r.firstSeen ?: "—"
        return "| $hit | $headline | ${r.category ?: "—"} | $conf | $impact | $quad | $dirMag | $horizon | $scope | $firstSeen |"
    }

    private fun statusHeader(st: String): String = when (st) {
        "pending" -> "⏳ 待定 (pending)"
        "watching" -> "👀 跟踪中 (watching)"
        "confirmed" -> "✅ 已证实 (confirmed)"
        "refuted" -> "❌ 已证伪 (refuted)"
        else -> "❓ $st"
    }
}
