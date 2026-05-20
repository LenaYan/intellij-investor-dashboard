package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Distilled "market breadth" snapshot extracted from market-research.md §2.1.
 * Used by the top-bar indicators (advancers/decliners, limit-up/down count, turnover).
 *
 * All fields are optional — the snapshot is best-effort and gracefully omits
 * fields we couldn't parse from the table.
 */
data class FinanceMarketSnapshot(
    val date: LocalDate,
    val advancers: Int?,
    val decliners: Int?,
    val limitUp: Int?,
    val limitDown: Int?,
    val turnoverYi: Double?,    // 成交额 (亿)，市值口径
    val northboundYi: Double?,  // 北向资金净买入 (亿)
)

internal object FinanceMarketSnapshotParser {

    private val ADV_DEC_RE = Regex("""涨跌家数.*?(\d{2,4})\s*[:：]\s*(\d{2,4})""")
    private val LIMIT_RE = Regex("""涨停[/／]?跌停.*?(\d{1,4}).*?[/／、]\s*(\d{1,4})""")
    private val TURNOVER_RE = Regex("""成交额.*?([0-9]+\.?[0-9]*)\s*万亿""")
    private val NORTHBOUND_RE = Regex("""北向.*?净.{0,3}入.*?(-?[0-9]+\.?[0-9]*)""")

    fun parseFromMarketResearch(financeDir: Path, date: LocalDate): FinanceMarketSnapshot? {
        val p = financeDir.resolve("reports").resolve(date.toString()).resolve("market-research.md")
        if (!Files.isRegularFile(p)) return null
        val md = try { Files.readString(p) } catch (_: Exception) { return null }
        val section = FinanceReportLocator.extractSection(md, "指数", "宽度", "市场") ?: md

        val advDec = ADV_DEC_RE.find(section)
        val advancers = advDec?.groupValues?.getOrNull(1)?.toIntOrNull()
        val decliners = advDec?.groupValues?.getOrNull(2)?.toIntOrNull()

        val limit = LIMIT_RE.find(section)
        val limitUp = limit?.groupValues?.getOrNull(1)?.toIntOrNull()
        val limitDown = limit?.groupValues?.getOrNull(2)?.toIntOrNull()

        val turnover = TURNOVER_RE.find(section)?.groupValues?.getOrNull(1)
            ?.toDoubleOrNull()?.let { it * 10_000.0 }   // 万亿 -> 亿

        val northbound = NORTHBOUND_RE.find(section)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

        if (advancers == null && limitUp == null && turnover == null) return null
        return FinanceMarketSnapshot(date, advancers, decliners, limitUp, limitDown, turnover, northbound)
    }
}
