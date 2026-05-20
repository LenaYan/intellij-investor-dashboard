package com.vermouthx.stocker.finance

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One position from ~/Claude/finance/portfolio.json.
 * The finance/ project's portfolio.json structure:
 *   { as_of, total_capital, cash, positions: [{ symbol, name, qty, cost, ... }] }
 *
 * We only consume the fields needed to mark a Stocker row as "held by user".
 */
data class PortfolioPosition(
    val symbol: String,
    val normalizedKey: String,
    val name: String?,
    val qty: Long?,
    val cost: Double?,
)

internal object FinancePortfolioParser {

    fun parse(jsonText: String): List<PortfolioPosition> {
        if (jsonText.isBlank()) return emptyList()
        val root = try {
            JsonParser.parseString(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        if (!root.isJsonObject) return emptyList()
        val positions = root.asJsonObject.get("positions") ?: return emptyList()
        if (!positions.isJsonArray) return emptyList()
        val out = ArrayList<PortfolioPosition>()
        positions.asJsonArray.forEach { el ->
            if (!el.isJsonObject) return@forEach
            val o = el.asJsonObject
            val sym = o.optString("symbol") ?: return@forEach
            out += PortfolioPosition(
                symbol = sym,
                normalizedKey = FinanceSymbol.normalize(sym),
                name = o.optString("name"),
                qty = o.optLong("qty"),
                cost = o.optDouble("cost"),
            )
        }
        return out
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

    private fun JsonObject.optLong(key: String): Long? {
        val v = this.get(key) ?: return null
        if (v.isJsonNull) return null
        return runCatching { v.asLong }.getOrNull()
    }
}
