package com.vermouthx.stocker.finance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

/**
 * Parses ~/Claude/finance/sector-tracker.json — the 板块强弱跟踪池 at the repo root
 * (not under reports/). Currently 14 sectors + 12 anomalies (2026-05-23).
 *
 * Schema:
 *   {
 *     "as_of": "2026-05-17",
 *     "today_anomalies": [ { symbol, name, sector, change_pct, note } ],
 *     "sectors": {
 *       "白酒": {
 *         "stocks": [ { symbol, name, change_pct } ],
 *         "today_avg": -2.1,           # optional
 *         "watch_for": "..."           # optional
 *       },
 *       ...
 *     }
 *   }
 */
data class SectorTracker(
    val asOf: String?,
    val anomalies: List<SectorAnomaly>,
    val sectors: List<SectorGroup>,
)

data class SectorAnomaly(
    val symbol: String,
    val name: String?,
    val sector: String?,
    val changePct: Double?,
    val note: String?,
)

data class SectorGroup(
    val sector: String,
    val stocks: List<SectorStock>,
    val todayAvg: Double?,
    val watchFor: String?,
) {
    /** Mean of stock change_pct values when explicit todayAvg missing. */
    val computedAvg: Double? by lazy {
        if (todayAvg != null) return@lazy todayAvg
        val pct = stocks.mapNotNull { it.changePct }
        if (pct.isEmpty()) null else pct.sum() / pct.size
    }
}

data class SectorStock(val symbol: String, val name: String?, val changePct: Double?)

internal object FinanceSectorTrackerLoader {

    fun load(financeDir: Path): SectorTracker? {
        val p = financeDir.resolve("sector-tracker.json")
        if (!Files.isRegularFile(p)) return null
        val raw = try { Files.readString(p) } catch (_: Exception) { return null }
        val root = try { JsonParser.parseString(raw).asJsonObject } catch (_: Exception) { return null }

        val anomalies = (root.get("today_anomalies") as? com.google.gson.JsonArray).orEmpty().mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val sym = o.optStr("symbol") ?: return@mapNotNull null
            SectorAnomaly(
                symbol = sym,
                name = o.optStr("name"),
                sector = o.optStr("sector"),
                changePct = o.optDouble("change_pct"),
                note = o.optStr("note"),
            )
        }
        val sectorsObj = root.get("sectors") as? JsonObject ?: JsonObject()
        val sectors = sectorsObj.entrySet().map { (sectorName, sectorEl) ->
            val so = sectorEl.asJsonObject
            val stocks = (so.get("stocks") as? com.google.gson.JsonArray).orEmpty().mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                val s = el.asJsonObject
                val sym = s.optStr("symbol") ?: return@mapNotNull null
                SectorStock(sym, s.optStr("name"), s.optDouble("change_pct"))
            }
            SectorGroup(
                sector = sectorName,
                stocks = stocks,
                todayAvg = so.optDouble("today_avg"),
                watchFor = so.optStr("watch_for"),
            )
        }
        return SectorTracker(
            asOf = root.optStr("as_of"),
            anomalies = anomalies,
            sectors = sectors,
        )
    }

    private fun JsonObject.optStr(k: String): String? {
        val v = this.get(k) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asString }.getOrNull()
    }

    private fun JsonObject.optDouble(k: String): Double? {
        val v = this.get(k) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asDouble }.getOrNull()
    }

    private fun com.google.gson.JsonArray?.orEmpty(): com.google.gson.JsonArray =
        this ?: com.google.gson.JsonArray()
}
