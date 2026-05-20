package com.vermouthx.stocker.finance

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Lightweight model of one row in ~/Claude/finance/watchlist.json.
 *
 * The finance/ project's schema (v2) per item:
 *   - symbol      "688981.SH"
 *   - name        "中芯国际"
 *   - sector      "半导体制造"
 *   - thesis      free text
 *   - trigger     free text (often "回踩 120-125 区间...")
 *   - target_zone [low, high] doubles
 *   - invalidation free text (often "跌破 110...")
 *   - ref_price   double
 */
data class WatchlistEntry(
    val symbol: String,
    val normalizedKey: String,
    val name: String?,
    val sector: String?,
    val thesis: String?,
    val trigger: String?,
    val targetZoneLow: Double?,
    val targetZoneHigh: Double?,
    val invalidation: String?,
    val refPrice: Double?,
) {
    /** First numeric value mentioned in invalidation text — heuristic stop-loss anchor. */
    val invalidationPrice: Double? by lazy {
        if (invalidation.isNullOrBlank()) return@lazy null
        FIRST_NUMBER_RE.find(invalidation)?.value?.toDoubleOrNull()
    }

    companion object {
        private val FIRST_NUMBER_RE = Regex("""\d+(?:\.\d+)?""")
    }
}

internal object FinanceWatchlistParser {

    private val gson = Gson()

    fun parse(jsonText: String): List<WatchlistEntry> {
        if (jsonText.isBlank()) return emptyList()
        val root = try {
            JsonParser.parseString(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonObject) return emptyList()
        val items = root.asJsonObject.get("items") ?: return emptyList()
        if (!items.isJsonArray) return emptyList()
        val out = ArrayList<WatchlistEntry>()
        items.asJsonArray.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val sym = o.optString("symbol") ?: return@forEach
            val (low, high) = parseTargetZone(o)
            out += WatchlistEntry(
                symbol = sym,
                normalizedKey = FinanceSymbol.normalize(sym),
                name = o.optString("name"),
                sector = o.optString("sector"),
                thesis = o.optString("thesis"),
                trigger = o.optString("trigger"),
                targetZoneLow = low,
                targetZoneHigh = high,
                invalidation = o.optString("invalidation"),
                refPrice = o.optDouble("ref_price"),
            )
        }
        return out
    }

    private fun parseTargetZone(o: JsonObject): Pair<Double?, Double?> {
        val tz = o.get("target_zone") ?: return null to null
        if (!tz.isJsonArray) return null to null
        val arr = tz.asJsonArray
        if (arr.size() < 2) return null to null
        val lo = arr[0].asDoubleOrNull()
        val hi = arr[1].asDoubleOrNull()
        // Ensure ordering
        return if (lo != null && hi != null && lo > hi) hi to lo else lo to hi
    }

    private fun JsonObject.optString(key: String): String? {
        val v = this.get(key) ?: return null
        return if (v.isJsonNull) null else runCatching { v.asString }.getOrNull()
    }

    private fun JsonObject.optDouble(key: String): Double? {
        val v = this.get(key) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asDouble }.getOrNull()
    }

    private fun com.google.gson.JsonElement.asDoubleOrNull(): Double? {
        if (isJsonNull) return null
        return runCatching { this.asDouble }.getOrNull()
    }
}
