package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * Locator for the finance/ project's daily reports directory and section extraction.
 *
 * Layout that ships from CLAUDE.md:
 *   ~/Claude/finance/reports/YYYY-MM-DD/{overnight-brief,market-research,flow-monitor,
 *                                         daily-review,anomaly-scanner,thread-tracker,
 *                                         position-risk-monitor,earnings-tracker,...}.md
 */
internal object FinanceReportLocator {

    val WELL_KNOWN_REPORTS = listOf(
        "daily-coordinator",
        "overnight-brief",
        "market-research",
        "midday-review",
        "daily-review",
        "anomaly-scanner",
        "flow-monitor",
        "news-radar",
        "news-radar-thematic",
        "thread-tracker",
        "theme-incubator",
        "candidate-ranker",
        "entry-timing",
        "position-risk-monitor",
        "earnings-tracker",
        "macro-radar",
        "sentiment-aggregator",
        "industry-mapping",
        "watchlist-screener",
        "weekly-review",
        "monthly-review",
    )

    fun today(): LocalDate = LocalDate.now(ZoneId.of("Asia/Shanghai"))

    fun reportDir(financeDir: Path, date: LocalDate = today()): Path =
        financeDir.resolve("reports").resolve(date.toString())

    fun reportPath(financeDir: Path, agent: String, date: LocalDate = today()): Path =
        reportDir(financeDir, date).resolve("$agent.md")

    /** Returns the list of report basenames (without .md) present today, in WELL_KNOWN order. */
    fun availableTodayReports(financeDir: Path, date: LocalDate = today()): List<String> {
        val dir = reportDir(financeDir, date)
        if (!Files.isDirectory(dir)) return emptyList()
        val present = try {
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".md") }
                    .map { it.removeSuffix(".md") }
                    .sorted()
                    .toList()
            }
        } catch (_: Exception) {
            return emptyList()
        }
        // Order by WELL_KNOWN first, then alphabetic remainder
        val ordered = LinkedHashSet<String>()
        WELL_KNOWN_REPORTS.forEach { if (present.contains(it)) ordered.add(it) }
        present.forEach { ordered.add(it) }
        return ordered.toList()
    }

    /**
     * Returns all report basenames matching a `prefix-*` pattern (e.g., `bull-bear-688981`,
     * `industry-mapping-AI端侧设备`, `style-jury-XXX`). Used to expose per-symbol /
     * per-theme variant reports without each one being hard-coded in WELL_KNOWN_REPORTS.
     */
    fun reportsMatchingPrefix(
        financeDir: Path,
        prefix: String,
        date: LocalDate = today(),
    ): List<String> {
        val dir = reportDir(financeDir, date)
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.list(dir).use { stream ->
                stream.map { it.fileName.toString() }
                    .filter { it.endsWith(".md") && it.startsWith("$prefix-") }
                    .map { it.removeSuffix(".md") }
                    .sorted()
                    .toList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Returns the most-recent dated directory back-tracking up to 7 days. */
    fun mostRecentReportDate(financeDir: Path, today: LocalDate = today()): LocalDate? {
        for (b in 0..7) {
            val d = today.minusDays(b.toLong())
            if (Files.isDirectory(reportDir(financeDir, d))) {
                if (availableTodayReports(financeDir, d).isNotEmpty()) return d
            }
        }
        return null
    }

    /** Read a report file safely; returns null on any error. */
    fun readReport(financeDir: Path, agent: String, date: LocalDate = today()): String? {
        val p = reportPath(financeDir, agent, date)
        if (!Files.isRegularFile(p)) return null
        return try {
            Files.readString(p)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extract the markdown section whose heading matches any of the supplied keywords.
     *
     * A "section" runs from a heading line (^#{1,6}\s+...) that contains at least one keyword
     * to the next heading line of the same or higher level (`##` stops at `##`/`#`).
     *
     * Returns null if no heading matches. Matching is case-insensitive and substring-based,
     * so passing "涨停" matches "## 2.3 涨停池结构变化".
     */
    fun extractSection(markdown: String, vararg keywords: String): String? {
        if (markdown.isBlank() || keywords.isEmpty()) return null
        val lines = markdown.lines()
        val lower = keywords.map { it.lowercase() }

        // find heading match
        var startIdx = -1
        var startLevel = -1
        for (i in lines.indices) {
            val ln = lines[i]
            val m = HEADING_RE.matchEntire(ln) ?: continue
            val hashes = m.groupValues[1]
            val heading = m.groupValues[2]
            val low = heading.lowercase()
            if (lower.any { kw -> low.contains(kw) }) {
                startIdx = i
                startLevel = hashes.length
                break
            }
        }
        if (startIdx < 0) return null

        // find end: next heading whose level <= startLevel
        var endIdx = lines.size
        for (i in (startIdx + 1) until lines.size) {
            val m = HEADING_RE.matchEntire(lines[i]) ?: continue
            if (m.groupValues[1].length <= startLevel) {
                endIdx = i
                break
            }
        }
        return lines.subList(startIdx, endIdx).joinToString("\n").trim()
    }

    /**
     * Get the top-of-report metadata block (— between two `---` lines) if present, plus the H1.
     * Used for "report preview" cards.
     */
    fun extractFrontMatterAndTitle(markdown: String): String {
        val lines = markdown.lines()
        if (lines.isEmpty()) return ""
        var idx = 0
        val out = StringBuilder()
        if (lines.firstOrNull()?.trim() == "---") {
            out.append(lines[0]).append('\n')
            idx = 1
            while (idx < lines.size && lines[idx].trim() != "---") {
                out.append(lines[idx]).append('\n'); idx++
            }
            if (idx < lines.size) {
                out.append(lines[idx]).append('\n'); idx++
            }
        }
        // first H1
        while (idx < lines.size && !lines[idx].startsWith("# ")) idx++
        if (idx < lines.size) {
            out.append('\n').append(lines[idx])
        }
        return out.toString().trim()
    }

    private val HEADING_RE = Regex("""^(#{1,6})\s+(.*)$""")
}
