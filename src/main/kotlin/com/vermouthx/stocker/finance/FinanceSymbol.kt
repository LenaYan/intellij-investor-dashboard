package com.vermouthx.stocker.finance

/**
 * Symbol normalization between Stocker's stored codes (e.g. "688981", "00700", "AAPL")
 * and finance/ project codes (e.g. "688981.SH", "00700.HK", "AAPL.US").
 *
 * Strategy: strip everything that isn't an alphanumeric core, uppercase, then compare.
 * For A-share, the pure 6-digit code is unique enough; for HK we keep leading zeros.
 */
object FinanceSymbol {

    @JvmStatic
    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val core = raw.substringBefore('.').trim()
        // remove any sh/sz/bj/us prefix Stocker might add internally
        val lowered = core.lowercase()
        val stripped = when {
            lowered.startsWith("sh") || lowered.startsWith("sz") || lowered.startsWith("bj") -> core.substring(2)
            lowered.startsWith("us") || lowered.startsWith("hk") -> core.substring(2)
            else -> core
        }
        return stripped.uppercase()
    }

    /**
     * Detect A-share board from a 6-digit code; returns the daily price-change limit in percent.
     *
     * Rules (mainland A-share 2026):
     *  - 60x / 00x  -> main board, ±10%
     *  - 30x        -> ChiNext, ±20% (since 2020 reform)
     *  - 688/689    -> Sci-tech (STAR), ±20%
     *  - 8/4/920    -> Beijing Exchange, ±30%
     *  - default    -> 10% (ST stocks are ±5% but we can't tell from code alone)
     */
    @JvmStatic
    fun limitPctOfAShare(code: String): Double {
        val n = normalize(code)
        if (n.length < 3) return 10.0
        return when {
            n.startsWith("688") || n.startsWith("689") -> 20.0
            n.startsWith("300") || n.startsWith("301") -> 20.0
            n.startsWith("8") || n.startsWith("4") || n.startsWith("920") -> 30.0
            else -> 10.0
        }
    }

    @JvmStatic
    fun isAShareCode(code: String): Boolean {
        val n = normalize(code)
        if (n.length != 6) return false
        return n.all { it.isDigit() }
    }
}
