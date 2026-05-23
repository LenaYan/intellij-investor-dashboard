package com.vermouthx.stocker.finance

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads ~/Claude/finance/judgments/rumors.jsonl — v2.4 二维矩阵 (置信度 × 影响) 台账.
 *
 * Each line is one JSON object:
 *   {
 *     "news_id": "2026-05-21-quant-regulation-rumor",
 *     "first_seen": "2026-05-21",
 *     "last_updated": "2026-05-23T12:07:27",
 *     "category": "小作文",                  // 宏观/产业/全球/小作文
 *     "headline": "...",
 *     "source_tier": "T3",                    // T0/T1/T2/T3
 *     "confidence_score": 27,                 // 0-100, mechanical
 *     "confidence_tier": "低",                // 高/中/低
 *     "impact_level": "高",                   // 高/中/低
 *     "quadrant": "⚠️ 噪音波动预警",
 *     "expected_impact": {
 *       "direction": "利空",
 *       "magnitude": "强",                    // 弱/中/强
 *       "scope": "大盘+小微盘+量化相关",
 *       "horizon": "脉冲"                     // 脉冲/短期/中期/长期
 *     },
 *     "status": "pending",                    // pending/watching/confirmed/refuted
 *     "resolution_date": null,
 *     "resolution_note": null,
 *     "actual_market_impact": null
 *   }
 */
data class RumorEntry(
    val newsId: String,
    val firstSeen: String?,
    val lastUpdated: String?,
    val category: String?,
    val headline: String,
    val sourceTier: String?,
    val confidenceScore: Int?,
    val confidenceTier: String?,
    val impactLevel: String?,
    val quadrant: String?,
    val direction: String?,
    val magnitude: String?,
    val scope: String?,
    val horizon: String?,
    val status: String,                     // pending/watching/confirmed/refuted
    val resolutionDate: String?,
    val resolutionNote: String?,
    val actualMarketImpact: String?,
)

internal object FinanceRumorLedgerLoader {

    fun load(financeDir: Path): List<RumorEntry> {
        val p = financeDir.resolve("judgments").resolve("rumors.jsonl")
        if (!Files.isRegularFile(p)) return emptyList()
        val out = ArrayList<RumorEntry>()
        try {
            Files.newBufferedReader(p).use { reader ->
                reader.lineSequence().forEach { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEach
                    val obj = try {
                        JsonParser.parseString(line).asJsonObject
                    } catch (_: Exception) {
                        return@forEach
                    }
                    val newsId = obj.get("news_id")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    val headline = obj.get("headline")?.takeIf { !it.isJsonNull }?.asString ?: return@forEach
                    val expected = if (obj.has("expected_impact") && obj.get("expected_impact").isJsonObject) {
                        obj.getAsJsonObject("expected_impact")
                    } else null
                    out.add(
                        RumorEntry(
                            newsId = newsId,
                            firstSeen = obj.optStr("first_seen"),
                            lastUpdated = obj.optStr("last_updated"),
                            category = obj.optStr("category"),
                            headline = headline,
                            sourceTier = obj.optStr("source_tier"),
                            confidenceScore = obj.optInt("confidence_score"),
                            confidenceTier = obj.optStr("confidence_tier"),
                            impactLevel = obj.optStr("impact_level"),
                            quadrant = obj.optStr("quadrant"),
                            direction = expected?.optStr("direction"),
                            magnitude = expected?.optStr("magnitude"),
                            scope = expected?.optStr("scope"),
                            horizon = expected?.optStr("horizon"),
                            status = obj.optStr("status") ?: "pending",
                            resolutionDate = obj.optStr("resolution_date"),
                            resolutionNote = obj.optStr("resolution_note"),
                            actualMarketImpact = obj.optStr("actual_market_impact"),
                        )
                    )
                }
            }
        } catch (_: Exception) {
            // ignore unreadable files
        }
        return out
    }

    private fun com.google.gson.JsonObject.optStr(k: String): String? {
        val v = this.get(k) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asString }.getOrNull()
    }

    private fun com.google.gson.JsonObject.optInt(k: String): Int? {
        val v = this.get(k) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asInt }.getOrNull()
            ?: runCatching { v.asString.toIntOrNull() }.getOrNull()
    }
}
