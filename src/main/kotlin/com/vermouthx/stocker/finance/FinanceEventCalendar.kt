package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * Reads the earnings / unlock calendar that earnings-tracker agent maintains
 * in ~/Claude/finance/reports/&lt;date&gt;/earnings-tracker.md.
 *
 * Strategy: parse all "(\d{6})" stock codes appearing within a 200-char window
 * after a "财报|earnings|解禁|unlock" keyword. Heuristic but cheap and robust
 * to formatting changes.
 */
object FinanceEventCalendar {

    enum class EventKind { EARNINGS, UNLOCK }

    data class Event(val symbol: String, val kind: EventKind)

    fun loadFromReports(financeDir: Path, date: LocalDate = FinanceReportLocator.today()): Map<String, Set<EventKind>> {
        val out = HashMap<String, MutableSet<EventKind>>()
        // earnings-tracker.md
        readMd(financeDir, "earnings-tracker", date)?.let { collect(it, out) }
        // position-risk-monitor.md may list restricted-release symbols too
        readMd(financeDir, "position-risk-monitor", date)?.let { collect(it, out) }
        // 7-day fallback so we don't blank out on weekends
        if (out.isEmpty()) {
            FinanceReportLocator.walkRecentDays(date, 1..7) { d ->
                readMd(financeDir, "earnings-tracker", d)?.let { collect(it, out) }
                out.takeIf { it.isNotEmpty() }
            }
        }
        return out
    }

    private fun readMd(financeDir: Path, agent: String, date: LocalDate): String? {
        val p = financeDir.resolve("reports").resolve(date.toString()).resolve("$agent.md")
        if (!Files.isRegularFile(p)) return null
        return try { Files.readString(p) } catch (_: Exception) { null }
    }

    private val KEYWORDS = mapOf(
        Regex("""(财报|业绩预告|earnings|预披露)""") to EventKind.EARNINGS,
        Regex("""(解禁|限售股|unlock|restricted)""") to EventKind.UNLOCK,
    )
    private val CODE_RE = Regex("""\b(\d{6})\b""")

    private fun collect(md: String, out: MutableMap<String, MutableSet<EventKind>>) {
        for ((re, kind) in KEYWORDS) {
            re.findAll(md).forEach { m ->
                val start = m.range.first
                val end = (start + 200).coerceAtMost(md.length)
                val window = md.substring(start, end)
                CODE_RE.findAll(window).forEach { codeMatch ->
                    val sym = FinanceSymbol.normalize(codeMatch.groupValues[1])
                    out.getOrPut(sym) { mutableSetOf() }.add(kind)
                }
            }
        }
    }
}
