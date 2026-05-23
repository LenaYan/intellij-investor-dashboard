package com.vermouthx.stocker.finance.panels

import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceSectorTrackerLoader
import com.vermouthx.stocker.finance.SectorAnomaly
import com.vermouthx.stocker.finance.SectorGroup
import com.vermouthx.stocker.finance.SectorTracker
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * 🌡️ 板块温度 Tab — renders sector-tracker.json as:
 *
 *   1. 今日异动榜（涨幅/跌幅 Top）
 *   2. 板块强弱排行（按 today_avg / computedAvg 降序）
 *   3. 每个板块的成分股 + watch_for note
 */
internal class FinanceSectorTrackerPanel : JPanel(BorderLayout()) {

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
        val tracker = FinanceSectorTrackerLoader.load(FinanceBridgeService.instance.financeDir())
        if (tracker == null || (tracker.sectors.isEmpty() && tracker.anomalies.isEmpty())) {
            viewer.setEmptyMessage(
                "找不到 sector-tracker.json。\n\n" +
                    "板块强弱池由 Claude 手动 / agent 维护。\n" +
                    "位置: ~/Claude/finance/sector-tracker.json"
            )
            return
        }
        viewer.setMarkdown(buildMarkdown(tracker))
    }

    private fun buildMarkdown(t: SectorTracker): String = buildString {
        appendLine("# 板块强弱榜 · sector-tracker")
        appendLine()
        appendLine("> as_of: ${t.asOf ?: "?"} · 板块 ${t.sectors.size} · 异动 ${t.anomalies.size}")
        appendLine()

        // ── 今日异动榜 ───────────────────────────────────────────────
        if (t.anomalies.isNotEmpty()) {
            appendLine("## 1. 今日异动榜")
            appendLine()
            appendLine("| 涨跌幅 | 标的 | 板块 | 备注 |")
            appendLine("|---|---|---|---|")
            t.anomalies.sortedByDescending { it.changePct ?: -999.0 }.forEach { a ->
                appendLine(anomalyRow(a))
            }
            appendLine()
        }

        // ── 板块强弱排行 ──────────────────────────────────────────────
        appendLine("## 2. 板块强弱排行")
        appendLine()
        appendLine("| 板块 | 板块均值 | 成分股 | 关注点 |")
        appendLine("|---|---|---|---|")
        val ranked = t.sectors.sortedByDescending { it.computedAvg ?: -999.0 }
        ranked.forEach { g ->
            val avg = g.computedAvg?.let { "%+.2f%%".format(it) } ?: "—"
            val glyph = sectorGlyph(g.computedAvg)
            val watch = (g.watchFor ?: "—").replace("|", "\\|").take(60)
            appendLine("| $glyph **${g.sector}** | $avg | ${g.stocks.size} 只 | $watch |")
        }
        appendLine()

        // ── 板块详情 ───────────────────────────────────────────────
        appendLine("## 3. 板块成分股明细")
        appendLine()
        ranked.forEach { g -> appendLine(sectorBody(g)) }
    }

    private fun anomalyRow(a: SectorAnomaly): String {
        val pct = a.changePct?.let { "%+.2f%%".format(it) } ?: "—"
        val name = (a.name ?: "—") + " (${a.symbol})"
        val sector = a.sector ?: "—"
        val note = (a.note ?: "—").replace("|", "\\|").take(80)
        val glyph = when {
            a.changePct == null -> ""
            a.changePct > 9.5 -> "🚀 "
            a.changePct > 5.0 -> "🔥 "
            a.changePct > 0.0 -> "🟢 "
            a.changePct < -9.5 -> "💀 "
            a.changePct < -5.0 -> "🔻 "
            else -> "🔴 "
        }
        return "| $glyph$pct | $name | $sector | $note |"
    }

    private fun sectorGlyph(avg: Double?): String = when {
        avg == null -> "⚪"
        avg > 3.0 -> "🔥"
        avg > 0.5 -> "🟢"
        avg < -3.0 -> "🔻"
        avg < -0.5 -> "🔴"
        else -> "⚪"
    }

    private fun sectorBody(g: SectorGroup): String = buildString {
        val avg = g.computedAvg?.let { "%+.2f%%".format(it) } ?: "—"
        appendLine("### ${sectorGlyph(g.computedAvg)} ${g.sector} · 均 $avg")
        appendLine()
        if (!g.watchFor.isNullOrBlank()) {
            appendLine("> 关注: ${g.watchFor}")
            appendLine()
        }
        if (g.stocks.isEmpty()) {
            appendLine("（无成分股数据）")
            appendLine()
            return@buildString
        }
        appendLine("| 标的 | 涨跌幅 |")
        appendLine("|---|---|")
        g.stocks.sortedByDescending { it.changePct ?: -999.0 }.forEach { s ->
            val pct = s.changePct?.let { "%+.2f%%".format(it) } ?: "—"
            val name = (s.name ?: "—") + " (${s.symbol})"
            appendLine("| $name | $pct |")
        }
        appendLine()
    }
}
