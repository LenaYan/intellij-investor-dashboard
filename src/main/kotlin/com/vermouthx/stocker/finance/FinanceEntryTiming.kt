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
/**
 * Structured trigger entry (v2.3 schema). Each entry is one of four shapes:
 *   - pullback : price entering [low, high] zone fires the trigger
 *   - breakout : price crossing above value fires the trigger
 *   - below    : price falling below value (used in invalidations_struct)
 *   - condition: non-price condition (MACD / event / etc.) — informational only
 */
data class EntryTimingStructTrigger(
    val type: String,                   // pullback | breakout | below | condition
    val value: Double?,                 // for breakout / below
    val low: Double?,                   // for pullback (low edge)
    val high: Double?,                  // for pullback (high edge)
    val action: String?,                // 接 1 成 / 追 1/2 / 清仓 / ...
    val positionPct: Int?,              // suggested position size for this rung
    val description: String?,           // for condition type (free text)
) {
    /** Primary anchor price for distance display. Null for `condition` type. */
    val anchorPrice: Double?
        get() = when (type) {
            "pullback" -> low                 // closer-to-current edge of the zone
            "breakout", "below" -> value
            else -> null
        }
}

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
    val triggers: List<String>,                                 // legacy free-text
    val invalidations: List<String>,                            // legacy free-text
    val triggersStruct: List<EntryTimingStructTrigger>,         // v2.3 — structured
    val invalidationsStruct: List<EntryTimingStructTrigger>,    // v2.3 — structured
) {
    /**
     * First anchor price from `triggers_struct` (pullback.low or breakout.value).
     * Falls back to first-number regex extraction on the free-text `triggers[]` list
     * for backward compatibility with pre-v2.3 reports.
     */
    val triggerPrice: Double? by lazy {
        triggersStruct.firstNotNullOfOrNull { it.anchorPrice?.takeIf { p -> p > 0.0 } }
            ?: firstNumeric(triggers)
    }

    /**
     * First `below.value` from `invalidations_struct`, falling back to free-text regex.
     */
    val invalidationPrice: Double? by lazy {
        invalidationsStruct.asSequence()
            .filter { it.type == "below" }
            .firstNotNullOfOrNull { it.value?.takeIf { p -> p > 0.0 } }
            ?: firstNumeric(invalidations)
    }

    /**
     * Primary pullback zone (low, high) if present in triggers_struct — null otherwise.
     * Used by DISTANCE column to highlight range-based triggers correctly (the
     * legacy single-anchor logic only checks ±1.5% of one point).
     */
    val pullbackZone: Pair<Double, Double>? by lazy {
        val pb = triggersStruct.firstOrNull { it.type == "pullback" } ?: return@lazy null
        val lo = pb.low ?: return@lazy null
        val hi = pb.high ?: return@lazy null
        if (lo > 0 && hi > 0 && lo <= hi) lo to hi else null
    }

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
        val snap = FinanceReportYaml.readJudgmentSnapshot(markdown) ?: return emptyList()
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
                totalScore = FinanceReportYaml.intAt(m, "total_score"),
                grade = m["grade"] as? String,
                entryType = m["entry_type"] as? String,
                resonanceScore = FinanceReportYaml.intAt(m, "resonance_score"),
                positionScore = FinanceReportYaml.intAt(m, "position_score"),
                eventState = m["event_state"] as? String,
                alignedThread = m["aligned_thread"] as? String,
                threadPhase = m["thread_phase"] as? String,
                firstPositionPct = FinanceReportYaml.intAt(m, "first_position_pct"),
                addSchedule = m["add_schedule"] as? String,
                triggers = asStringList(m["triggers"]),
                invalidations = asStringList(m["invalidations"]),
                triggersStruct = parseStructList(m["triggers_struct"]),
                invalidationsStruct = parseStructList(m["invalidations_struct"]),
            )
        }
        return out
    }

    private fun asStringList(v: Any?): List<String> {
        if (v !is List<*>) return emptyList()
        return v.mapNotNull { it?.toString() }
    }

    /**
     * Parse a `triggers_struct` / `invalidations_struct` list. Each entry is a
     * map with `type` plus type-specific fields (value/low/high/action/etc).
     * Unknown types are kept as `condition` so they don't break parsing.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseStructList(v: Any?): List<EntryTimingStructTrigger> {
        if (v !is List<*>) return emptyList()
        return v.mapNotNull { item ->
            if (item !is Map<*, *>) return@mapNotNull null
            val m = item as Map<String, Any?>
            val type = (m["type"] as? String)?.lowercase()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            EntryTimingStructTrigger(
                type = type,
                value = FinanceReportYaml.doubleAt(m, "value"),
                low = FinanceReportYaml.doubleAt(m, "low"),
                high = FinanceReportYaml.doubleAt(m, "high"),
                action = m["action"] as? String,
                positionPct = FinanceReportYaml.intAt(m, "position_pct"),
                description = m["description"] as? String,
            )
        }
    }
}

internal object FinanceEntryTimingLoader {

    /** Load today's entry-timing.md (falling back 5 days if missing). */
    fun loadFromReports(
        financeDir: Path,
        today: LocalDate = FinanceReportLocator.today(),
    ): List<EntryTimingRecommendation> =
        FinanceReportLocator.walkRecentDays(today, 0..5) { d ->
            FinanceReportLocator.readReport(financeDir, "entry-timing", d)
                ?.let { FinanceEntryTimingParser.parse(it) }
                ?.takeIf { it.isNotEmpty() }
        }.orEmpty()
}
