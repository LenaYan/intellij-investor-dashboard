package com.vermouthx.stocker.finance

import java.nio.file.Path
import java.time.LocalDate

/**
 * news-radar.md / news-radar-thematic.md parser.
 *
 * news-radar agent ships a v2.4 YAML schema:
 *   judgment_snapshot:
 *     report_type: 消息情报
 *     high_confidence_events:
 *       - { news_id, headline, category, source_tier, confidence_score,
 *           freshness, direction, impact_level, scope, matrix_quadrant,
 *           related_thread }
 *     rumor_ledger_today:    # mid/low confidence — also appended to rumors.jsonl
 *       - { ... same shape ... }
 *     policy_deductions:
 *       - { policy, purpose, follow_up_path, confidence }
 *     price_volume_reconciliation:
 *       index: 创业板, change_pct: 2.84,
 *       explained_by: [ "..." ],
 *       unexplained_gap: false | "..."
 *
 * The plugin only consumes a flat subset for the dashboard table — the full
 * report stays in the Reports browser for deep reading.
 */
data class NewsRadarEvent(
    val newsId: String?,
    val headline: String,
    val category: String?,            // 宏观 / 产业 / 全球 / 小作文 / ...
    val sourceTier: String?,          // T0 / T1 / T2 / T3
    val confidenceScore: Int?,        // 0-100
    val freshness: String?,           // 新发生(0d) / 发酵中(2d) / 陈旧(14d) / ...
    val direction: String?,           // 利多 / 利空 / 中性
    val impactLevel: String?,         // 高 / 中 / 低
    val scope: String?,               // 板块/主线 scope
    val matrixQuadrant: String?,      // 纳入主线推理 / 观察跟踪 / ⚠️ 噪音波动预警 / 记录归档
    val relatedThread: String?,
)

data class NewsRadarReconciliation(
    val index: String?,
    val changePct: Double?,
    val explainedBy: List<String>,
    val unexplainedGap: String?,      // null/false → no gap; string → the gap description
)

data class NewsRadarPolicyDeduction(
    val policy: String,
    val purpose: String?,
    val followUpPath: List<String>,
    val confidence: Int?,
)

data class NewsRadarReport(
    val date: LocalDate,
    val highConfidenceEvents: List<NewsRadarEvent>,
    val rumorLedgerToday: List<NewsRadarEvent>,
    val policyDeductions: List<NewsRadarPolicyDeduction>,
    val reconciliation: NewsRadarReconciliation?,
    val sourceBasename: String,       // "news-radar" or "news-radar-thematic"
)

internal object FinanceNewsRadarLoader {

    /**
     * Load today's news-radar.md (and -thematic variant) with 5-day fallback.
     * Returns the highest-priority variant first (plain > thematic).
     */
    fun loadFromReports(
        financeDir: Path,
        today: LocalDate = FinanceReportLocator.today(),
    ): List<NewsRadarReport> {
        for (b in 0..5) {
            val d = today.minusDays(b.toLong())
            val out = ArrayList<NewsRadarReport>()
            listOf("news-radar", "news-radar-thematic").forEach { basename ->
                val md = FinanceReportLocator.readReport(financeDir, basename, d) ?: return@forEach
                parse(md, d, basename)?.let { out.add(it) }
            }
            if (out.isNotEmpty()) return out
        }
        return emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(md: String, date: LocalDate, basename: String): NewsRadarReport? {
        val yaml = FinanceReportYaml.extractLastYamlBlock(md) ?: return null
        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree

        val high = (snap["high_confidence_events"] as? List<Any?>).orEmpty()
            .mapNotNull { parseEvent(it as? Map<String, Any?> ?: return@mapNotNull null) }
        val rumors = (snap["rumor_ledger_today"] as? List<Any?>).orEmpty()
            .mapNotNull { parseEvent(it as? Map<String, Any?> ?: return@mapNotNull null) }
        val policies = (snap["policy_deductions"] as? List<Any?>).orEmpty()
            .mapNotNull { parsePolicy(it as? Map<String, Any?> ?: return@mapNotNull null) }

        val recon = (snap["price_volume_reconciliation"] as? Map<String, Any?>)?.let { m ->
            NewsRadarReconciliation(
                index = m["index"]?.toString(),
                changePct = asDouble(m["change_pct"]),
                explainedBy = (m["explained_by"] as? List<Any?>).orEmpty().mapNotNull { it?.toString() },
                unexplainedGap = when (val g = m["unexplained_gap"]) {
                    null, false, "false" -> null
                    is String -> g.takeIf { it.isNotBlank() && it.lowercase() != "false" }
                    else -> g.toString()
                },
            )
        }

        if (high.isEmpty() && rumors.isEmpty() && policies.isEmpty() && recon == null) return null
        return NewsRadarReport(date, high, rumors, policies, recon, basename)
    }

    private fun parseEvent(m: Map<String, Any?>): NewsRadarEvent? {
        val headline = (m["headline"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return NewsRadarEvent(
            newsId = m["news_id"] as? String,
            headline = headline,
            category = m["category"] as? String,
            sourceTier = m["source_tier"] as? String,
            confidenceScore = asInt(m["confidence_score"]),
            freshness = m["freshness"] as? String,
            direction = m["direction"] as? String,
            impactLevel = m["impact_level"] as? String,
            scope = m["scope"] as? String,
            matrixQuadrant = m["matrix_quadrant"] as? String ?: m["quadrant"] as? String,
            relatedThread = m["related_thread"] as? String,
        )
    }

    private fun parsePolicy(m: Map<String, Any?>): NewsRadarPolicyDeduction? {
        val policy = (m["policy"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return NewsRadarPolicyDeduction(
            policy = policy,
            purpose = m["purpose"] as? String,
            followUpPath = (m["follow_up_path"] as? List<Any?>).orEmpty().mapNotNull { it?.toString() },
            confidence = asInt(m["confidence"]),
        )
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Double -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun asDouble(v: Any?): Double? = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
