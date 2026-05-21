package com.vermouthx.stocker.finance

/**
 * Parsed shape of the `thread_scenario_tree` block emitted by `market-research` agent
 * (see `docs/yaml-schema.md` §4.1.5).
 *
 * The tree describes 2-4 branch hypotheses for how the thread's leader will move
 * next, each anchored on a machine-readable price trigger. The plugin matches the
 * leader's live quote against these triggers to tell the user which branch the
 * market is currently following (or whether it's running outside every branch).
 */
data class ThreadScenarioTree(
    val leaderSymbol: String,            // raw form, "688256.SH" / "AAPL.US" / "00700.HK"
    val leaderName: String?,             // optional display name
    val leaderRefPrice: Double,          // anchor price (yesterday's close, usually)
    val refPriceDate: String?,           // "2026-05-20"
    val branches: List<ScenarioBranch>,
    val outOfScopeUpper: Double?,        // strictly > leaderRefPrice
    val outOfScopeLower: Double?,        // strictly < leaderRefPrice
    val outOfScopeNote: String?,
) {
    /** Stocker-side normalized key (e.g. "688256") for matching against StockerQuote.code. */
    val leaderKey: String get() = FinanceSymbol.normalize(leaderSymbol)
}

enum class PriceTriggerType { ABOVE, BELOW, RANGE, UNKNOWN }

data class ScenarioBranch(
    val id: String,                      // "A_主升加速"
    val condition: String?,              // human-readable condition text
    val priceTriggerType: PriceTriggerType,
    val priceTriggerValue: Double?,      // above/below threshold
    val priceTriggerLow: Double?,        // range low
    val priceTriggerHigh: Double?,       // range high
    val priceTriggerDays: Int?,          // range duration requirement (optional)
    val volumeYiGte: Double?,            // optional volume floor (in 亿)
    val nextPhase: String?,              // "主升" | "分歧" | "退潮" | ...
    val confidence: Double,              // 0..1
    val action: String?,                 // operating guidance text
) {
    /** Single best display label (A/B/C prefix already in id by convention). */
    val displayLabel: String get() = id
}

internal object FinanceScenarioTreeParser {

    /**
     * Build a [ThreadScenarioTree] from a `judgment_snapshot.thread_scenario_tree` map.
     * Returns null when required fields are missing (parser is intentionally lenient
     * on optional fields but strict on leader_symbol / leader_ref_price / branches).
     */
    @Suppress("UNCHECKED_CAST")
    fun fromYaml(snap: Map<String, Any?>): ThreadScenarioTree? {
        val tree = snap["thread_scenario_tree"] as? Map<String, Any?> ?: return null
        val leaderSymbol = (tree["leader_symbol"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val refPrice = asDouble(tree["leader_ref_price"]) ?: return null
        val rawBranches = tree["branches"] as? List<Any?> ?: return null
        val branches = rawBranches.mapNotNull { parseBranch(it) }
        if (branches.isEmpty()) return null

        val oos = tree["out_of_scope_threshold"] as? Map<String, Any?>
        return ThreadScenarioTree(
            leaderSymbol = leaderSymbol,
            leaderName = tree["leader_name"] as? String,
            leaderRefPrice = refPrice,
            refPriceDate = tree["ref_price_date"]?.toString(),
            branches = branches,
            outOfScopeUpper = asDouble(oos?.get("upper")),
            outOfScopeLower = asDouble(oos?.get("lower")),
            outOfScopeNote = oos?.get("note") as? String,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseBranch(raw: Any?): ScenarioBranch? {
        if (raw !is Map<*, *>) return null
        val m = raw as Map<String, Any?>
        val id = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val confidence = asDouble(m["confidence"]) ?: 0.0
        val pt = m["price_trigger"] as? Map<String, Any?>
        val typeStr = (pt?.get("type") as? String)?.lowercase() ?: ""
        val type = when (typeStr) {
            "above" -> PriceTriggerType.ABOVE
            "below" -> PriceTriggerType.BELOW
            "range" -> PriceTriggerType.RANGE
            else -> PriceTriggerType.UNKNOWN
        }
        return ScenarioBranch(
            id = id,
            condition = m["condition"] as? String,
            priceTriggerType = type,
            priceTriggerValue = asDouble(pt?.get("value")),
            priceTriggerLow = asDouble(pt?.get("low")),
            priceTriggerHigh = asDouble(pt?.get("high")),
            priceTriggerDays = asInt(pt?.get("days")),
            volumeYiGte = asDouble(pt?.get("volume_yi_gte")),
            nextPhase = m["next_phase"] as? String,
            confidence = confidence,
            action = m["action"] as? String,
        )
    }

    private fun asDouble(v: Any?): Double? = when (v) {
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    private fun asInt(v: Any?): Int? = when (v) {
        is Int -> v
        is Double -> v.toInt()
        is Long -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
