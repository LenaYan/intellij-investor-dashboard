package com.vermouthx.stocker.finance

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Roll-up of ~/Claude/finance/judgments/YYYY-MM.jsonl files.
 *
 * Each line is one judgment snapshot (one agent's report on a given day).
 * For the dashboard we summarise:
 *   - last 30-day judgment count by agent
 *   - last 30-day daily-review `direction_accuracy` outcomes (hit/partial/miss/reverse)
 *   - daily-review `failure_signals_results` hit ratio
 *
 * Cheap (line-by-line parse, bounded by 30 days * ~13 agents ≈ 400 lines).
 */
internal object FinanceJudgmentsAggregator {

    data class Stats(
        val days: Int,
        val totalJudgments: Int,
        val byAgent: Map<String, Int>,
        val directionAccuracy: Map<String, Int>,  // "hit"/"partial"/"miss"/"reverse"
        val failureSignalsHits: Int,
        val failureSignalsTotal: Int,
        val systematicBiases: List<String>,
    )

    fun aggregate(financeDir: Path, days: Int = 30, today: LocalDate = LocalDate.now()): Stats? {
        val root = financeDir.resolve("judgments")
        if (!Files.isDirectory(root)) return null

        val cutoff = today.minusDays((days - 1).toLong())
        val months = mutableSetOf<YearMonth>()
        var d = cutoff
        while (!d.isAfter(today)) {
            months.add(YearMonth.from(d))
            d = d.plusDays(1)
        }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM")
        val byAgent = LinkedHashMap<String, Int>()
        val direction = LinkedHashMap<String, Int>()
        val biases = LinkedHashSet<String>()
        var total = 0
        var fsHits = 0
        var fsTotal = 0

        for (m in months) {
            val file = root.resolve(m.format(fmt) + ".jsonl")
            if (!Files.isRegularFile(file)) continue
            try {
                Files.newBufferedReader(file).use { reader ->
                    reader.lineSequence().forEach { raw ->
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) return@forEach
                        val obj = try {
                            JsonParser.parseString(line).asJsonObject
                        } catch (_: Exception) {
                            return@forEach
                        }
                        val dateStr = obj.opt("date") ?: obj.opt("snapshot_date") ?: return@forEach
                        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@forEach
                        if (date.isBefore(cutoff) || date.isAfter(today)) return@forEach

                        total++
                        val agent = obj.opt("agent") ?: "?"
                        byAgent[agent] = (byAgent[agent] ?: 0) + 1

                        // daily-review specific aggregation
                        if (agent == "daily-review") {
                            obj.opt("direction_accuracy")?.let { dir ->
                                direction[dir] = (direction[dir] ?: 0) + 1
                            }
                            obj.opt("failure_signals_results_hit")?.toIntOrNull()?.let { fsHits += it }
                            obj.opt("failure_signals_results_total")?.toIntOrNull()?.let { fsTotal += it }
                            obj.optStringArray("systematic_bias").forEach { biases.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {
                // ignore unreadable monthly file
            }
        }

        return Stats(
            days = days,
            totalJudgments = total,
            byAgent = byAgent,
            directionAccuracy = direction,
            failureSignalsHits = fsHits,
            failureSignalsTotal = fsTotal,
            systematicBiases = biases.toList(),
        )
    }

    private fun com.google.gson.JsonObject.opt(key: String): String? {
        val v = this.get(key) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asString }.getOrNull()
            ?: runCatching { v.asNumber.toString() }.getOrNull()
    }

    private fun com.google.gson.JsonObject.optStringArray(key: String): List<String> {
        val v = this.get(key) ?: return emptyList()
        if (!v.isJsonArray) return emptyList()
        return v.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
    }
}
