package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Per-item calibration extracted from today's daily-review.md.
 *
 * daily-review.md YAML structure (v2):
 *   judgment_snapshot:
 *     calibration:
 *       direction_accuracy: hit | partial | miss | reverse
 *       failure_signals_results:
 *         - { signal: "...", outcome: hit/miss/not_evaluable }
 *       catalysts_due_today:
 *         - { catalyst: "...", outcome: ... }
 *       thread_continuity:
 *         yesterday_thread, today_thread, phase_evolution, health_trend, ...
 *     reviewed_reports:
 *       - { agent: overnight-brief, date: yesterday, snapshot_found: true, ... }
 *
 * We surface these as a flat list so the dashboard can render a left/right pred-vs-actual view.
 */
data class CalibrationItem(
    val kind: Kind,
    val source: String,           // overnight-brief / market-research / aggregate
    val sourceDate: LocalDate?,
    val text: String,             // the prediction text
    val outcome: Outcome,
    val detail: String?,          // optional extra context (e.g. yesterday→today thread name)
) {
    enum class Kind { FAILURE_SIGNAL, CATALYST, THREAD_PHASE, THREAD_HEALTH, NAMING_DRIFT, ROTATION, DIRECTION }
    enum class Outcome { HIT, PARTIAL, MISS, REVERSE, NOT_EVALUABLE, UNKNOWN }
}

data class CalibrationSummary(
    val reviewedDate: LocalDate?,
    val totalItems: Int,
    val hit: Int,
    val miss: Int,
    val reverse: Int,
    val notEvaluable: Int,
    val direction: CalibrationItem.Outcome,
    val reviewedReports: List<String>, // "overnight-brief@2026-05-20"
    val systematicBias: List<String>,
)

internal object FinanceCalibrationLoader {

    /**
     * Read today's daily-review.md and convert its calibration block into items + summary.
     * Returns (emptyList, null) when no daily-review.md exists yet.
     *
     * Falls back to the most recent daily-review within 7 days when today's is missing —
     * we still want users to see *yesterday's* review in the morning before tonight's runs.
     */
    fun load(
        financeDir: Path,
        today: LocalDate = FinanceReportLocator.today(),
    ): Pair<List<CalibrationItem>, CalibrationSummary?> {
        return FinanceReportLocator.walkRecentDays(today, 0..7) { d ->
            FinanceReportLocator.readReport(financeDir, "daily-review", d)
                ?.let { parseFromMarkdown(it, reviewedDate = d) }
                ?.takeIf { it.second != null }
        } ?: (emptyList<CalibrationItem>() to null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseFromMarkdown(md: String, reviewedDate: LocalDate): Pair<List<CalibrationItem>, CalibrationSummary?> {
        val snap = FinanceReportYaml.readJudgmentSnapshot(md) ?: return emptyList<CalibrationItem>() to null
        val cal = FinanceReportYaml.mapAt(snap, "calibration") ?: return emptyList<CalibrationItem>() to null

        val items = ArrayList<CalibrationItem>()
        val direction = parseOutcome(cal["direction_accuracy"]?.toString())

        items += CalibrationItem(
            kind = CalibrationItem.Kind.DIRECTION,
            source = "overnight-brief + market-research",
            sourceDate = reviewedDate.minusDays(1),
            text = "昨日方向判断（direction_accuracy）",
            outcome = direction,
            detail = null,
        )

        (cal["failure_signals_results"] as? List<Any?>)?.forEach { item ->
            if (item !is Map<*, *>) return@forEach
            @Suppress("UNCHECKED_CAST")
            val m = item as Map<String, Any?>
            val signal = m["signal"]?.toString() ?: return@forEach
            items += CalibrationItem(
                kind = CalibrationItem.Kind.FAILURE_SIGNAL,
                source = "overnight-brief/market-research",
                sourceDate = reviewedDate.minusDays(1),
                text = signal,
                outcome = parseOutcome(m["outcome"]?.toString()),
                detail = null,
            )
        }

        (cal["catalysts_due_today"] as? List<Any?>)?.forEach { item ->
            if (item !is Map<*, *>) return@forEach
            @Suppress("UNCHECKED_CAST")
            val m = item as Map<String, Any?>
            val cat = m["catalyst"]?.toString() ?: return@forEach
            items += CalibrationItem(
                kind = CalibrationItem.Kind.CATALYST,
                source = "next_catalyst",
                sourceDate = reviewedDate.minusDays(1),
                text = cat,
                outcome = parseOutcome(m["outcome"]?.toString()),
                detail = null,
            )
        }

        val tc = FinanceReportYaml.mapAt(cal, "thread_continuity")
        if (tc != null) {
            val yT = tc["yesterday_thread"]?.toString()
            val tT = tc["today_thread"]?.toString()
            val phaseEvolution = tc["phase_evolution"]?.toString()
            val healthTrend = tc["health_trend"]?.toString()
            val namingDrift = tc["naming_drift"] == true
            val rotPred = tc["rotation_predicted"]?.toString()
            val rotAct = tc["rotation_actual"]?.toString()

            items += CalibrationItem(
                kind = CalibrationItem.Kind.THREAD_PHASE,
                source = "thread_continuity",
                sourceDate = reviewedDate.minusDays(1),
                text = "主线 phase 演化：${yT ?: "?"} → ${tT ?: "?"}",
                outcome = when (phaseEvolution) {
                    "hit_natural" -> CalibrationItem.Outcome.HIT
                    "inverted" -> CalibrationItem.Outcome.REVERSE
                    "not_evaluable" -> CalibrationItem.Outcome.NOT_EVALUABLE
                    else -> CalibrationItem.Outcome.UNKNOWN
                },
                detail = if (phaseEvolution == "inverted") "实际走势与预期反向" else null,
            )

            if (healthTrend != null) {
                items += CalibrationItem(
                    kind = CalibrationItem.Kind.THREAD_HEALTH,
                    source = "thread_continuity",
                    sourceDate = reviewedDate.minusDays(1),
                    text = "主线 health 趋势预期",
                    outcome = when (healthTrend) {
                        "hit" -> CalibrationItem.Outcome.HIT
                        "missed" -> CalibrationItem.Outcome.MISS
                        "not_evaluable" -> CalibrationItem.Outcome.NOT_EVALUABLE
                        else -> CalibrationItem.Outcome.UNKNOWN
                    },
                    detail = null,
                )
            }

            if (namingDrift) {
                items += CalibrationItem(
                    kind = CalibrationItem.Kind.NAMING_DRIFT,
                    source = "thread_continuity",
                    sourceDate = reviewedDate.minusDays(1),
                    text = "主线命名漂移（昨日今日写法不一致）",
                    outcome = CalibrationItem.Outcome.MISS,
                    detail = "${yT ?: "?"} vs ${tT ?: "?"}",
                )
            }

            if (!rotPred.isNullOrBlank() || !rotAct.isNullOrBlank()) {
                val hit = (rotPred == "true" && rotAct == "true") || (rotPred == "false" && rotAct == "false")
                items += CalibrationItem(
                    kind = CalibrationItem.Kind.ROTATION,
                    source = "thread_continuity",
                    sourceDate = reviewedDate.minusDays(1),
                    text = "龙头轮换预测",
                    outcome = if (hit) CalibrationItem.Outcome.HIT else CalibrationItem.Outcome.MISS,
                    detail = "预测=${rotPred ?: "null"} 实际=${rotAct ?: "null"}",
                )
            }
        }

        val reviewedReports = (snap["reviewed_reports"] as? List<Any?>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val agent = m["agent"]?.toString() ?: return@mapNotNull null
            val date = m["date"]?.toString() ?: return@mapNotNull null
            val found = m["snapshot_found"]
            val tag = if (found == true) "" else " (未找到)"
            "$agent@$date$tag"
        }

        val systematicBias = (snap["systematic_bias"] as? List<Any?>).orEmpty().mapNotNull { it?.toString() }

        var hit = 0; var miss = 0; var rev = 0; var ne = 0
        for (it in items) {
            when (it.outcome) {
                CalibrationItem.Outcome.HIT, CalibrationItem.Outcome.PARTIAL -> hit++
                CalibrationItem.Outcome.MISS -> miss++
                CalibrationItem.Outcome.REVERSE -> rev++
                CalibrationItem.Outcome.NOT_EVALUABLE -> ne++
                CalibrationItem.Outcome.UNKNOWN -> {}
            }
        }
        val summary = CalibrationSummary(
            reviewedDate = reviewedDate,
            totalItems = items.size,
            hit = hit,
            miss = miss,
            reverse = rev,
            notEvaluable = ne,
            direction = direction,
            reviewedReports = reviewedReports,
            systematicBias = systematicBias,
        )
        return items to summary
    }

    private fun parseOutcome(s: String?): CalibrationItem.Outcome {
        return when (s?.lowercase()?.trim()) {
            "hit" -> CalibrationItem.Outcome.HIT
            "partial" -> CalibrationItem.Outcome.PARTIAL
            "miss" -> CalibrationItem.Outcome.MISS
            "reverse" -> CalibrationItem.Outcome.REVERSE
            "not_evaluable", "n/a", "na" -> CalibrationItem.Outcome.NOT_EVALUABLE
            else -> CalibrationItem.Outcome.UNKNOWN
        }
    }
}
