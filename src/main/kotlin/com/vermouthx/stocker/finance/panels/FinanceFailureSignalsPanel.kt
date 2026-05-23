package com.vermouthx.stocker.finance.panels

import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.messages.MessageBusConnection
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.finance.FailureSignal
import com.vermouthx.stocker.finance.FailureSignalStatus
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.finance.FinanceFailureSignalsLoader
import com.vermouthx.stocker.finance.FinanceSymbol
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 🎯 证伪信号实时盘 Tab — for each predicted `failure_signals[]` from yesterday's
 * market-research / overnight-brief, show whether it has been triggered today.
 *
 * State legend:
 *   ✅ HIT       — 信号已触发（agent 当时预言的"如果...就证伪"成立）
 *   ❌ NOT_HIT   — 信号未触发（agent 判断有效）
 *   ⏸ PENDING   — 无法自动判定（自由文本，agent 在 daily-review 里手动 calibrate）
 *
 * Wired to live quote topic so that 6-digit code signals refresh inline.
 */
internal class FinanceFailureSignalsPanel : JPanel(BorderLayout()) {

    private val viewer = FinanceMarkdownViewer()
    private val refreshHook: () -> Unit = { reload() }
    private var messageBusConnection: MessageBusConnection? = null

    private var signals: List<FailureSignal> = emptyList()
    private val latestQuotes = HashMap<String, StockerQuote>()

    init {
        add(viewer, BorderLayout.CENTER)
        FinanceBridgeService.instance.addRefreshListener(refreshHook)
        messageBusConnection = ApplicationManager.getApplication().messageBus.connect().apply {
            subscribe(StockerQuoteUpdateNotifier.STOCK_ALL_QUOTE_UPDATE_TOPIC,
                object : StockerQuoteUpdateNotifier {
                    override fun syncQuotes(quotes: List<StockerQuote>, size: Int) {
                        ingestQuotes(quotes)
                    }

                    override fun syncIndices(indices: List<StockerQuote>) {
                        ingestQuotes(indices)
                    }
                })
        }
        reload()
    }

    fun dispose() {
        FinanceBridgeService.instance.removeRefreshListener(refreshHook)
        messageBusConnection?.disconnect()
        messageBusConnection = null
    }

    private fun ingestQuotes(quotes: List<StockerQuote>) {
        var dirty = false
        for (q in quotes) {
            val key = FinanceSymbol.normalize(q.code)
            val prior = latestQuotes[key]
            if (prior == null || prior.current != q.current) {
                latestQuotes[key] = q
                dirty = true
            }
        }
        if (dirty) {
            SwingUtilities.invokeLater { render() }
        }
    }

    private fun reload() {
        signals = FinanceFailureSignalsLoader.loadYesterdaySignals(FinanceBridgeService.instance.financeDir())
        render()
    }

    private fun render() {
        if (signals.isEmpty()) {
            viewer.setEmptyMessage(
                "找不到昨日的 failure_signals。\n\n" +
                    "证伪信号由 market-research / overnight-brief agent 在 YAML 中嵌入。\n" +
                    "示例 (yaml-schema.md §3.3)：\n" +
                    "  failure_signals:\n" +
                    "    - \"上证指数收盘跌破 3350\"\n" +
                    "    - \"北向资金 30 分钟净流出 > 50 亿\""
            )
            return
        }

        val statuses = signals.map { evaluate(it) }
        viewer.setMarkdown(buildMarkdown(statuses))
    }

    private fun evaluate(s: FailureSignal): FailureSignalStatus {
        if (!s.autoCheckable || s.threshold == null) {
            return FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "自由文本 — 需 daily-review 手动校准")
        }
        return when (s.unit) {
            FailureSignal.Unit.PRICE -> {
                // Index lookup — find quote by name "上证指数" / "sh000001" etc.
                val q = lookupIndexQuote(s.parsedSymbol)
                if (q == null) {
                    FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "等待 ${s.parsedSymbol} 行情")
                } else {
                    val px = q.current
                    val hit = when (s.direction) {
                        FailureSignal.Direction.BELOW -> px < s.threshold
                        FailureSignal.Direction.ABOVE -> px > s.threshold
                        else -> false
                    }
                    val deltaPct = (px - s.threshold) / s.threshold * 100.0
                    FailureSignalStatus(
                        s,
                        if (hit) FailureSignalStatus.State.HIT else FailureSignalStatus.State.NOT_HIT,
                        "${s.parsedSymbol} ¥${"%.2f".format(px)} (相对阈值 ${"%+.2f%%".format(deltaPct)})"
                    )
                }
            }
            FailureSignal.Unit.PERCENT -> {
                val code = s.parsedSymbol ?: return FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "无标的代码")
                val q = latestQuotes[FinanceSymbol.normalize(code)]
                if (q == null) {
                    FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "等待 $code 行情")
                } else {
                    val pct = q.percentage
                    val hit = when (s.direction) {
                        FailureSignal.Direction.BELOW -> pct < -s.threshold
                        FailureSignal.Direction.ABOVE -> pct > s.threshold
                        else -> false
                    }
                    FailureSignalStatus(
                        s,
                        if (hit) FailureSignalStatus.State.HIT else FailureSignalStatus.State.NOT_HIT,
                        "$code ${"%+.2f%%".format(pct)} (阈值 ${"%+.1f%%".format(if (s.direction == FailureSignal.Direction.BELOW) -s.threshold else s.threshold)})"
                    )
                }
            }
            FailureSignal.Unit.AMOUNT_YI -> {
                // Northbound flow — pull from market snapshot
                val snap = FinanceBridgeService.instance.snapshot().marketSnapshot
                if (snap?.northboundYi == null) {
                    FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "等待北向资金数据")
                } else {
                    val nb = snap.northboundYi
                    // "净流出 > 50亿" = -nb > 50 (nb is signed)
                    val outflow = -nb
                    val hit = when {
                        s.rawText.contains("流出") -> outflow > s.threshold
                        s.rawText.contains("流入") -> nb > s.threshold
                        else -> kotlin.math.abs(nb) > s.threshold
                    }
                    FailureSignalStatus(
                        s,
                        if (hit) FailureSignalStatus.State.HIT else FailureSignalStatus.State.NOT_HIT,
                        "北向今日 ${"%+.1f".format(nb)} 亿 (阈值 ${s.threshold} 亿)"
                    )
                }
            }
            else -> FailureSignalStatus(s, FailureSignalStatus.State.PENDING, "无法解析")
        }
    }

    private fun lookupIndexQuote(name: String?): StockerQuote? {
        if (name == null) return null
        // Indices show up via syncIndices; the quote.name typically matches.
        return latestQuotes.values.firstOrNull { q ->
            (q.name == name || q.code.contains(name) || q.name.contains(name)) && q.current > 0
        }
    }

    private fun buildMarkdown(statuses: List<FailureSignalStatus>): String = buildString {
        appendLine("# 证伪信号实时盘 · failure_signals")
        appendLine()
        val src = statuses.first().signal
        appendLine("> 信号源: ${src.sourceAgent} @ ${src.signalDate} · 共 ${statuses.size} 条")
        val hit = statuses.count { it.state == FailureSignalStatus.State.HIT }
        val notHit = statuses.count { it.state == FailureSignalStatus.State.NOT_HIT }
        val pending = statuses.count { it.state == FailureSignalStatus.State.PENDING }
        appendLine("> 当前状态: ✅ $hit · ❌ $notHit · ⏸ $pending")
        appendLine()

        appendLine("| 状态 | 证伪信号 | 实时观察 | 来源 |")
        appendLine("|---|---|---|---|")
        statuses.forEach { st ->
            val glyph = when (st.state) {
                FailureSignalStatus.State.HIT -> "✅ 已触发"
                FailureSignalStatus.State.NOT_HIT -> "❌ 未触发"
                FailureSignalStatus.State.PENDING -> "⏸ 待定"
                FailureSignalStatus.State.UNKNOWN -> "❓"
            }
            val sig = st.signal.rawText.replace("|", "\\|").take(80)
            val obs = st.observation.replace("|", "\\|")
            appendLine("| $glyph | $sig | $obs | ${st.signal.sourceAgent} |")
        }
        appendLine()

        if (hit > 0) {
            appendLine("---")
            appendLine()
            appendLine("⚠️ **已触发 $hit 条证伪信号 — 主线 thesis 需要重审**")
        }
    }
}
