package com.vermouthx.stocker.finance

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-thread snapshot of the finance/ project state that Stocker UI needs.
 *
 * Reloaded by [FinanceFileWatcher] whenever watchlist.json / portfolio.json /
 * reports/<today>/position-risk-monitor.md changes on disk.
 *
 * Lookups are by normalized symbol (see [FinanceSymbol.normalize]).
 */
class FinanceState {

    enum class Health { GREEN, YELLOW, RED, UNKNOWN }

    data class Snapshot(
        val watchlistBySymbol: Map<String, WatchlistEntry>,
        val portfolioBySymbol: Map<String, PortfolioPosition>,
        val healthBySymbol: Map<String, Health>,
        val eventsBySymbol: Map<String, Set<FinanceEventCalendar.EventKind>>,
        val marketSnapshot: FinanceMarketSnapshot?,
        val mainThread: String?,
        val threadPhase: String?,
        val threadAgeDays: Int?,
        val reportDate: LocalDate?,
    ) {
        companion object {
            val EMPTY = Snapshot(emptyMap(), emptyMap(), emptyMap(), emptyMap(), null, null, null, null, null)
        }
    }

    private val current = AtomicReference(Snapshot.EMPTY)

    fun get(): Snapshot = current.get()

    fun watchlistEntry(code: String?): WatchlistEntry? {
        if (code.isNullOrBlank()) return null
        return current.get().watchlistBySymbol[FinanceSymbol.normalize(code)]
    }

    fun healthOf(code: String?): Health {
        if (code.isNullOrBlank()) return Health.UNKNOWN
        return current.get().healthBySymbol[FinanceSymbol.normalize(code)] ?: Health.UNKNOWN
    }

    fun eventsOf(code: String?): Set<FinanceEventCalendar.EventKind> {
        if (code.isNullOrBlank()) return emptySet()
        return current.get().eventsBySymbol[FinanceSymbol.normalize(code)] ?: emptySet()
    }

    fun reload(financeDir: Path) {
        val watchlist = readWatchlist(financeDir.resolve("watchlist.json"))
        val portfolio = readPortfolio(financeDir.resolve("portfolio.json"))

        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val (health, mainThread, phase, ageDays, reportDate) = readHealthFromReports(financeDir, today)

        // If today's position-risk-monitor doesn't exist, fall back to scanning the
        // last 5 days so a stale-but-readable status is still better than UNKNOWN.
        val finalHealth = if (health.isNotEmpty()) health else fallbackHealth(financeDir, today)

        val events = FinanceEventCalendar.loadFromReports(financeDir, today)
        val marketSnap = FinanceMarketSnapshotParser.parseFromMarketResearch(financeDir, today)
            ?: run {
                // fallback to previous trading day
                var s: FinanceMarketSnapshot? = null
                for (b in 1..3) {
                    s = FinanceMarketSnapshotParser.parseFromMarketResearch(financeDir, today.minusDays(b.toLong()))
                    if (s != null) break
                }
                s
            }

        current.set(
            Snapshot(
                watchlistBySymbol = watchlist.associateBy { it.normalizedKey },
                portfolioBySymbol = portfolio.associateBy { it.normalizedKey },
                healthBySymbol = finalHealth,
                eventsBySymbol = events,
                marketSnapshot = marketSnap,
                mainThread = mainThread,
                threadPhase = phase,
                threadAgeDays = ageDays,
                reportDate = reportDate,
            )
        )
    }

    fun reset() {
        current.set(Snapshot.EMPTY)
    }

    // ── private ────────────────────────────────────────────────────────────────

    private fun readWatchlist(p: Path): List<WatchlistEntry> {
        if (!Files.isRegularFile(p)) return emptyList()
        return try {
            FinanceWatchlistParser.parse(Files.readString(p))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readPortfolio(p: Path): List<PortfolioPosition> {
        if (!Files.isRegularFile(p)) return emptyList()
        return try {
            FinancePortfolioParser.parse(Files.readString(p))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class HealthRead(
        val health: Map<String, Health>,
        val mainThread: String?,
        val phase: String?,
        val ageDays: Int?,
        val reportDate: LocalDate?,
    )

    private fun readHealthFromReports(financeDir: Path, date: LocalDate): HealthRead {
        val healthMap = LinkedHashMap<String, Health>()
        val day = financeDir.resolve("reports").resolve(date.toString())

        val riskPath = day.resolve("position-risk-monitor.md")
        if (Files.isRegularFile(riskPath)) {
            extractHealthFromRiskReport(Files.readString(riskPath), healthMap)
        }

        var mainThread: String? = null
        var phase: String? = null
        var ageDays: Int? = null
        val mrPath = day.resolve("market-research.md")
        if (Files.isRegularFile(mrPath)) {
            val yaml = FinanceReportYaml.extractLastYamlBlock(Files.readString(mrPath))
            if (yaml != null) {
                val tree = FinanceReportYaml.parseSimpleYaml(yaml)
                val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree
                mainThread = snap["main_thread"] as? String
                phase = snap["thread_phase"] as? String
                ageDays = (snap["thread_age_days"] as? Int)
                    ?: (snap["thread_age_days"] as? Double)?.toInt()
            }
        }

        return HealthRead(
            health = healthMap,
            mainThread = mainThread,
            phase = phase,
            ageDays = ageDays,
            reportDate = if (Files.isRegularFile(riskPath)) date else null,
        )
    }

    private fun fallbackHealth(financeDir: Path, today: LocalDate): Map<String, Health> {
        for (back in 1..5) {
            val d = today.minusDays(back.toLong())
            val p = financeDir.resolve("reports").resolve(d.toString()).resolve("position-risk-monitor.md")
            if (Files.isRegularFile(p)) {
                val m = LinkedHashMap<String, Health>()
                try {
                    extractHealthFromRiskReport(Files.readString(p), m)
                } catch (_: Exception) {
                }
                if (m.isNotEmpty()) return m
            }
        }
        return emptyMap()
    }

    /**
     * Build per-symbol health from the YAML block of a position-risk-monitor.md.
     *
     * Mapping rules (per docs/yaml-schema.md §4.3 / §4.4):
     *   - any symbol in `triggered`        -> RED
     *   - any symbol in `near_trigger`     -> YELLOW
     *   - `thesis_drift[].drift_score`:
     *        0 / not present  -> GREEN
     *        1 - 2            -> YELLOW
     *        3+               -> RED
     *   - if `portfolio_health` is "警戒", upgrade GREEN to YELLOW for anyone unspecified
     */
    private fun extractHealthFromRiskReport(md: String, out: MutableMap<String, Health>) {
        val yaml = FinanceReportYaml.extractLastYamlBlock(md) ?: return
        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree

        // 1) thesis_drift => default GREEN for everyone listed
        @Suppress("UNCHECKED_CAST")
        val drift = snap["thesis_drift"] as? List<Any?>
        drift?.forEach { item ->
            if (item is Map<*, *>) {
                val sym = item["symbol"]?.toString() ?: return@forEach
                val score = (item["drift_score"] as? Int)
                    ?: (item["drift_score"] as? Double)?.toInt()
                    ?: 0
                val key = FinanceSymbol.normalize(sym)
                out[key] = when {
                    score >= 3 -> Health.RED
                    score >= 1 -> Health.YELLOW
                    else -> Health.GREEN
                }
            }
        }

        // 2) near_trigger upgrades anyone listed to YELLOW
        @Suppress("UNCHECKED_CAST")
        val near = snap["near_trigger"] as? List<Any?>
        near?.forEach { item ->
            if (item is Map<*, *>) {
                val sym = item["symbol"]?.toString() ?: return@forEach
                val key = FinanceSymbol.normalize(sym)
                val cur = out[key] ?: Health.GREEN
                if (cur != Health.RED) out[key] = Health.YELLOW
            }
        }

        // 3) triggered overrides anything to RED
        @Suppress("UNCHECKED_CAST")
        val triggered = snap["triggered"] as? List<Any?>
        triggered?.forEach { item ->
            if (item is Map<*, *>) {
                val sym = item["symbol"]?.toString() ?: return@forEach
                out[FinanceSymbol.normalize(sym)] = Health.RED
            }
        }
    }
}
