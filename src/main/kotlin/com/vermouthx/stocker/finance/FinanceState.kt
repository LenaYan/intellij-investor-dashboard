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
        // ── v2 additions ────────────────────────────────────────────────
        val entryTimingBySymbol: Map<String, EntryTimingRecommendation>,
        val entryTimingAll: List<EntryTimingRecommendation>,
        /** Phase yesterday (most recent prior trading day with market-research). Null if unknown. */
        val priorThreadPhase: String?,
        val priorMainThread: String?,
        val leaderRotation: Boolean,
        val priorLeader: String?,
        val currentLeader: String?,
        /** Last up-to-3 daily thread_health_score samples (oldest → newest). */
        val threadHealthSeries: List<Int>,
        /** daily-review calibration items for today (predictions vs reality). */
        val calibrationItems: List<CalibrationItem>,
        val calibrationSummary: CalibrationSummary?,
    ) {
        companion object {
            val EMPTY = Snapshot(
                watchlistBySymbol = emptyMap(),
                portfolioBySymbol = emptyMap(),
                healthBySymbol = emptyMap(),
                eventsBySymbol = emptyMap(),
                marketSnapshot = null,
                mainThread = null,
                threadPhase = null,
                threadAgeDays = null,
                reportDate = null,
                entryTimingBySymbol = emptyMap(),
                entryTimingAll = emptyList(),
                priorThreadPhase = null,
                priorMainThread = null,
                leaderRotation = false,
                priorLeader = null,
                currentLeader = null,
                threadHealthSeries = emptyList(),
                calibrationItems = emptyList(),
                calibrationSummary = null,
            )
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

    fun entryTimingOf(code: String?): EntryTimingRecommendation? {
        if (code.isNullOrBlank()) return null
        return current.get().entryTimingBySymbol[FinanceSymbol.normalize(code)]
    }

    fun reload(financeDir: Path) {
        val watchlist = readWatchlist(financeDir.resolve("watchlist.json"))
        val portfolio = readPortfolio(financeDir.resolve("portfolio.json"))

        val today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val (health, mainThread, phase, ageDays, reportDate, leader) = readHealthFromReports(financeDir, today)

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

        // entry-timing: today's grades + triggers + invalidations
        val entryList = FinanceEntryTimingLoader.loadFromReports(financeDir, today)
        val entryMap = entryList.associateBy { it.normalizedKey }

        // Prior-day thread snapshot (for "phase 跃迁 / leader 轮换" highlighting)
        val prior = readPriorThreadSnapshot(financeDir, today)
        val rotation = leader != null && prior.leader != null && leader != prior.leader
        val healthSeries = readThreadHealthSeries(financeDir, today, lookbackDays = 3)

        // daily-review calibration items
        val (calItems, calSummary) = FinanceCalibrationLoader.load(financeDir, today)

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
                entryTimingBySymbol = entryMap,
                entryTimingAll = entryList,
                priorThreadPhase = prior.phase,
                priorMainThread = prior.mainThread,
                leaderRotation = rotation,
                priorLeader = prior.leader,
                currentLeader = leader,
                threadHealthSeries = healthSeries,
                calibrationItems = calItems,
                calibrationSummary = calSummary,
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
        val leader: String?,
    )

    private fun readHealthFromReports(financeDir: Path, date: LocalDate): HealthRead {
        val healthMap = LinkedHashMap<String, Health>()
        val day = financeDir.resolve("reports").resolve(date.toString())

        val riskPath = day.resolve("position-risk-monitor.md")
        if (Files.isRegularFile(riskPath)) {
            extractHealthFromRiskReport(Files.readString(riskPath), healthMap)
        }

        val ts = readThreadSnapshot(financeDir, date)
        return HealthRead(
            health = healthMap,
            mainThread = ts.mainThread,
            phase = ts.phase,
            ageDays = ts.ageDays,
            reportDate = if (Files.isRegularFile(riskPath)) date else null,
            leader = ts.leader,
        )
    }

    private data class ThreadSnapshot(
        val mainThread: String?,
        val phase: String?,
        val ageDays: Int?,
        val leader: String?,
        val healthScore: Int?,
        val date: LocalDate?,
    )

    /** Read main_thread/phase/age/leader/health from market-research.md YAML of a given date. */
    private fun readThreadSnapshot(financeDir: Path, date: LocalDate): ThreadSnapshot {
        val mrPath = financeDir.resolve("reports").resolve(date.toString()).resolve("market-research.md")
        if (!Files.isRegularFile(mrPath)) return ThreadSnapshot(null, null, null, null, null, null)
        val yaml = FinanceReportYaml.extractLastYamlBlock(Files.readString(mrPath))
            ?: return ThreadSnapshot(null, null, null, null, null, null)
        val tree = FinanceReportYaml.parseSimpleYaml(yaml)
        val snap = FinanceReportYaml.mapAt(tree, "judgment_snapshot") ?: tree
        val mainThread = snap["main_thread"] as? String
        val phase = snap["thread_phase"] as? String
        val ageDays = (snap["thread_age_days"] as? Int)
            ?: (snap["thread_age_days"] as? Double)?.toInt()
        val healthScore = (snap["thread_health_score"] as? Int)
            ?: (snap["thread_health_score"] as? Double)?.toInt()

        // leader: prefer `current_leader` scalar, else top of `leader_rotation` list's `current`,
        // else first item in `leaders` list, else null.
        val leader = (snap["current_leader"] as? String)
            ?: extractLeaderFromRotation(snap)
            ?: extractFirstLeader(snap)
        return ThreadSnapshot(mainThread, phase, ageDays, leader, healthScore, date)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractLeaderFromRotation(snap: Map<String, Any?>): String? {
        val rot = snap["leader_rotation"]
        if (rot is Map<*, *>) {
            (rot as Map<String, Any?>)["current"]?.toString()?.let { return it }
        }
        if (rot is List<*>) {
            val last = rot.lastOrNull()
            if (last is Map<*, *>) {
                (last as Map<String, Any?>)["current"]?.toString()?.let { return it }
            }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractFirstLeader(snap: Map<String, Any?>): String? {
        val leaders = snap["leaders"] as? List<Any?> ?: return null
        val first = leaders.firstOrNull() ?: return null
        return when (first) {
            is String -> first
            is Map<*, *> -> (first as Map<String, Any?>)["symbol"]?.toString()
                ?: first["name"]?.toString()
            else -> null
        }
    }

    /** Walk back up to 7 days to find the prior trading day's thread snapshot. */
    private fun readPriorThreadSnapshot(financeDir: Path, today: LocalDate): ThreadSnapshot {
        for (b in 1..7) {
            val d = today.minusDays(b.toLong())
            val ts = readThreadSnapshot(financeDir, d)
            if (ts.phase != null || ts.mainThread != null) return ts
        }
        return ThreadSnapshot(null, null, null, null, null, null)
    }

    /** Latest [lookbackDays] thread_health_score values, oldest → newest. */
    private fun readThreadHealthSeries(financeDir: Path, today: LocalDate, lookbackDays: Int): List<Int> {
        val out = ArrayList<Int>()
        for (b in (lookbackDays - 1) downTo 0) {
            val d = today.minusDays(b.toLong())
            val s = readThreadSnapshot(financeDir, d).healthScore
            if (s != null) out.add(s)
        }
        return out
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
