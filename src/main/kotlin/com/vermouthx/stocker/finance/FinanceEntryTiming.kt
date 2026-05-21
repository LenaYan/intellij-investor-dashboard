package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Reads ~/Claude/finance/reports/&lt;date&gt;/entry-timing.md and exposes the
 * recommendations list keyed by normalized symbol.
 *
 * entry-timing.md YAML schema (v2):
 *   recommendations:
 *     - symbol: "688981"
 *       name: "中芯国际"
 *       total_score: 128
 *       grade: "A+"            # A+ | A | B | C | 不买
 *       entry_type: 突破启动    # 趋势加速 | 回踩低吸 | 突破启动 | 反转左侧
 *       resonance_score: 8
 *       position_score: 75
 *       event_state: 催化前夕
 *       aligned_thread: "半导体国产替代"
 *       thread_phase: 发酵
 *       first_position_pct: 40
 *       add_schedule: "4:3:3"
 *       triggers:        [ "...", "..." ]
 *       invalidations:   [ "...", "..." ]
 */
data class EntryTimingRecommendation(
    val symbol: String,
    val normalizedKey: String,
    val name: String?,
    val totalScore: Int?,
    val grade: String?,                // A+ / A / B / C / 不买
    val entryType: String?,            // 突破启动 / 回踩低吸 / 趋势加速 / 反转左侧
    val resonanceScore: Int?,          // 0-10
    val positionScore: Int?,           // 0-100
    val eventState: String?,           // 真空期 / 催化前夕 / 兑现后 / 解禁压制
    val alignedThread: String?,
    val threadPhase: String?,
    val firstPositionPct: Int?,
    val addSchedule: String?,
    val triggers: List<String>,
    val invalidations: List<String>,
) {
    /** First numeric value mentioned in any trigger line — used as a real-time crossing anchor. */
    val triggerPrice: Double? by lazy { firstNumeric(triggers) }

    /** First numeric value mentioned in any invalidation line — used as the stop-loss anchor. */
    val invalidationPrice: Double? by lazy { firstNumeric(invalidations) }

    private fun firstNumeric(items: List<String>): Double? {
        for (s in items) {
            val m = FIRST_NUMBER_RE.find(s) ?: continue
            // Skip percentages — usually noise like "-3%".
            val end = m.range.last + 1
            if (end < s.length && s[end] == '%') continue
            val v = m.value.toDoubleOrNull() ?: continue
            if (v > 0.0) return v
        }
        return null
    }

    /** Health colour suggestion for the badge prefix in NAME column. */
    val gradeGlyph: String?
        get() = when (grade?.trim()) {
            "A+" -> "🟢A+"
            "A" -> "🟢A"
            "B" -> "🟡B"
            "C" -> "🔴C"
            "不买" -> "⛔"
            else -> null
        }

    companion object {
        private val FIRST_NUMBER_RE = Regex("""\d+(?:\.\d+)?""")
    }
}

internal object FinanceEntryTimingParser {

    /** Parse entry-timing.md content; returns list of recommendations or empty. */
    fun parse(markdown: String): List<EntryTimingRecommendation> {
        val yaml = FinanceReportYaml.extractLastYamlBlock(markdown) ?: return emptyList()
        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree
        @Suppress("UNCHECKED_CAST")
        val list = snap["recommendations"] as? List<Any?> ?: return emptyList()
        val out = ArrayList<EntryTimingRecommendation>()
        for (item in list) {
            if (item !is Map<*, *>) continue
            @Suppress("UNCHECKED_CAST")
            val m = item as Map<String, Any?>
            val sym = m["symbol"]?.toString() ?: continue
            if (sym.isBlank()) continue
            out += EntryTimingRecommendation(
                symbol = sym,
                normalizedKey = FinanceSymbol.normalize(sym),
                name = m["name"] as? String,
                totalScore = asInt(m["total_score"]),
                grade = m["grade"] as? String,
                entryType = m["entry_type"] as? String,
                resonanceScore = asInt(m["resonance_score"]),
                positionScore = asInt(m["position_score"]),
                eventState = m["event_state"] as? String,
                alignedThread = m["aligned_thread"] as? String,
                threadPhase = m["thread_phase"] as? String,
                firstPositionPct = asInt(m["first_position_pct"]),
                addSchedule = m["add_schedule"] as? String,
                triggers = asStringList(m["triggers"]),
                invalidations = asStringList(m["invalidations"]),
            )
        }
        return out
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Double -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asStringList(v: Any?): List<String> {
        if (v !is List<*>) return emptyList()
        return v.mapNotNull { it?.toString() }
    }
}

internal object FinanceEntryTimingLoader {

    /** Load today's entry-timing.md (falling back 5 days if missing). */
    fun loadFromReports(
        financeDir: Path,
        today: LocalDate = FinanceReportLocator.today(),
    ): List<EntryTimingRecommendation> {
        for (b in 0..5) {
            val d = today.minusDays(b.toLong())
            val md = FinanceReportLocator.readReport(financeDir, "entry-timing", d) ?: continue
            val parsed = FinanceEntryTimingParser.parse(md)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }
}
