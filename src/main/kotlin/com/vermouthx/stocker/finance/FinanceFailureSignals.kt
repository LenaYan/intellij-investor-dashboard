package com.vermouthx.stocker.finance

import java.nio.file.Path
import java.time.LocalDate

/**
 * Load yesterday's predicted `failure_signals[]` from the most recent
 * market-research.md (or overnight-brief.md) and pair them with live data
 * for ✅/❌/⏸ classification.
 *
 * Failure signal pattern recognition (best-effort heuristic — agents are still
 * writing these as free text; we extract anchor numbers + symbol codes when
 * possible):
 *
 *   "上证指数收盘跌破 3350"           → check index threshold
 *   "北向资金 30 分钟净流出 > 50 亿"   → check northbound flow magnitude
 *   "寒武纪低开 >3% 且 5 分钟无反包"   → check symbol change_pct
 *
 * For each parsed signal we expose what we **could** check vs what we
 * couldn't (`autoCheckable`).
 */
data class FailureSignal(
    val rawText: String,
    val signalDate: LocalDate,        // the date this signal was predicted FOR
    val sourceAgent: String,           // "market-research" / "overnight-brief"
    val parsedSymbol: String?,         // 6-digit code or "上证指数" / "北向" / null
    val direction: Direction,
    val threshold: Double?,            // anchor number (price or %)
    val unit: Unit,
    val autoCheckable: Boolean,
) {
    enum class Direction { BELOW, ABOVE, MAGNITUDE, UNKNOWN }
    enum class Unit { PRICE, PERCENT, AMOUNT_YI, UNKNOWN }
}

data class FailureSignalStatus(
    val signal: FailureSignal,
    val state: State,
    val observation: String,           // "上证 3389.45 (>3350, +1.18%)"
) {
    enum class State { HIT, NOT_HIT, PENDING, UNKNOWN }
}

internal object FinanceFailureSignalsLoader {

    /** Extract yesterday's failure_signals as raw + parsed list. */
    fun loadYesterdaySignals(
        financeDir: Path,
        today: LocalDate = FinanceReportLocator.today(),
    ): List<FailureSignal> {
        // Try yesterday first, then walk back 5 days for non-trading days.
        for (b in 1..5) {
            val d = today.minusDays(b.toLong())
            val out = ArrayList<FailureSignal>()
            listOf("market-research", "overnight-brief").forEach { agent ->
                val md = FinanceReportLocator.readReport(financeDir, agent, d) ?: return@forEach
                val list = extractFailureSignals(md) ?: return@forEach
                list.forEach { raw ->
                    out.add(parseSignal(raw, d, agent))
                }
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFailureSignals(md: String): List<String>? {
        val yaml = FinanceReportYaml.extractLastYamlBlock(md) ?: return null
        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree
        val list = snap["failure_signals"] as? List<Any?> ?: return null
        return list.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
    }

    /** Heuristically classify one signal into (symbol, direction, threshold, unit). */
    private fun parseSignal(raw: String, date: LocalDate, agent: String): FailureSignal {
        val text = raw.trim()

        // Pattern 1: index thresholds — 上证指数收盘跌破 3350
        val indexMatch = INDEX_BELOW_RE.find(text)
        if (indexMatch != null) {
            val sym = indexMatch.groupValues[1]
            val price = indexMatch.groupValues[2].toDoubleOrNull()
            return FailureSignal(
                rawText = text,
                signalDate = date,
                sourceAgent = agent,
                parsedSymbol = sym,
                direction = FailureSignal.Direction.BELOW,
                threshold = price,
                unit = FailureSignal.Unit.PRICE,
                autoCheckable = price != null,
            )
        }
        val indexAboveMatch = INDEX_ABOVE_RE.find(text)
        if (indexAboveMatch != null) {
            return FailureSignal(
                rawText = text,
                signalDate = date,
                sourceAgent = agent,
                parsedSymbol = indexAboveMatch.groupValues[1],
                direction = FailureSignal.Direction.ABOVE,
                threshold = indexAboveMatch.groupValues[2].toDoubleOrNull(),
                unit = FailureSignal.Unit.PRICE,
                autoCheckable = true,
            )
        }

        // Pattern 2: northbound flow magnitude — 北向资金 30 分钟净流出 > 50 亿
        val northMatch = NORTHBOUND_RE.find(text)
        if (northMatch != null) {
            val amt = northMatch.groupValues[1].toDoubleOrNull()
            return FailureSignal(
                rawText = text,
                signalDate = date,
                sourceAgent = agent,
                parsedSymbol = "北向",
                direction = FailureSignal.Direction.MAGNITUDE,
                threshold = amt,
                unit = FailureSignal.Unit.AMOUNT_YI,
                autoCheckable = true,
            )
        }

        // Pattern 3: 6-digit code + change% — 寒武纪低开 >3%
        val codeMatch = CODE_PERCENT_RE.find(text)
        if (codeMatch != null) {
            return FailureSignal(
                rawText = text,
                signalDate = date,
                sourceAgent = agent,
                parsedSymbol = codeMatch.groupValues[1],
                direction = if (text.contains("跌") || text.contains("低开")) FailureSignal.Direction.BELOW
                else FailureSignal.Direction.ABOVE,
                threshold = codeMatch.groupValues[2].toDoubleOrNull(),
                unit = FailureSignal.Unit.PERCENT,
                autoCheckable = true,
            )
        }

        // Default: unparseable — surfaces as PENDING (user judges)
        return FailureSignal(
            rawText = text,
            signalDate = date,
            sourceAgent = agent,
            parsedSymbol = null,
            direction = FailureSignal.Direction.UNKNOWN,
            threshold = null,
            unit = FailureSignal.Unit.UNKNOWN,
            autoCheckable = false,
        )
    }

    private val INDEX_BELOW_RE = Regex("""(上证指数?|深证成指?|创业板指?|沪深300|科创50|北证50).{0,8}?(?:跌破|下破|跌穿)\s*(\d{3,5}(?:\.\d+)?)""")
    private val INDEX_ABOVE_RE = Regex("""(上证指数?|深证成指?|创业板指?|沪深300|科创50|北证50).{0,8}?(?:站稳|站上|突破)\s*(\d{3,5}(?:\.\d+)?)""")
    private val NORTHBOUND_RE = Regex("""北向.{0,12}?(?:净流出|净卖出|净流入|净买入)\s*[>＞]?\s*(\d+(?:\.\d+)?)\s*亿""")
    private val CODE_PERCENT_RE = Regex("""(\d{6}).{0,8}?[>＞]?\s*(\d+(?:\.\d+)?)\s*%""")
}
