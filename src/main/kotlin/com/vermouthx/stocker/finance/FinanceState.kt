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
        /**
         * Primary scenario tree to drive the ScenarioPanel by default. Comes from
         * market-research.md when present, else the first active_threads[] tree
         * from thread-tracker.md.
         */
        val scenarioTree: ThreadScenarioTree?,
        /**
         * All known scenario trees (today + 5-day fallback), keyed by display label.
         * Multi-thread case: thread-tracker emits one per active_thread; combined
         * with market-research's primary tree (if distinct).
         *
         * Label = "<thread_name>" or "<thread_name> · <thread_sub>" when a thread
         * has sub-line splits. ScenarioPanel uses this to render a selector when
         * size > 1.
         */
        val scenarioTrees: Map<String, ThreadScenarioTree>,
        /**
         * Main_thread naming drift groups detected across today's reports
         * (CLAUDE.md red line #3 protection). Empty list = no drift.
         */
        val canonicalDrifts: List<CanonicalDrift>,
        /**
         * Liquidity environment parsed from macro-radar.md YAML
         * (`liquidity_env: 宽松/中性/紧缩`). Null if not present.
         */
        val liquidityEnv: String?,
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
                scenarioTree = null,
                scenarioTrees = emptyMap(),
                canonicalDrifts = emptyList(),
                liquidityEnv = null,
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
            ?: FinanceReportLocator.walkRecentDays(today, 1..3) { d ->
                FinanceMarketSnapshotParser.parseFromMarketResearch(financeDir, d)
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

        // thread_scenario_tree: market-research.md (primary) + thread-tracker.md
        // (per-active-thread). 5-day fallback walk.
        val scenarioMap = readAllScenarioTrees(financeDir, today)
        val scenarioTree = scenarioMap.values.firstOrNull()

        // canonical thread drift scan (red line #3)
        val drifts = try { FinanceCanonicalThreads.detect(financeDir, today) } catch (_: Exception) { emptyList() }
        val liquidity = readLiquidityEnv(financeDir, today)

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
                scenarioTree = scenarioTree,
                scenarioTrees = scenarioMap,
                canonicalDrifts = drifts,
                liquidityEnv = liquidity,
            )
        )
    }

    /**
     * Read macro-radar.md and extract the liquidity environment.
     * Strategy (in order):
     *   1. YAML `judgment_snapshot.liquidity_env` (preferred — agent should emit)
     *   2. YAML `judgment_snapshot.liquidity_environment`
     *   3. YAML `judgment_snapshot.macro.liquidity_env`
     *   4. YAML `judgment_snapshot.title` — heuristic match for "流动性<X>"
     *   5. Markdown body — substring match for "流动性环境定级" + "宽松/中性/紧缩"
     */
    private fun readLiquidityEnv(financeDir: Path, today: LocalDate): String? {
        for (b in 0..5) {
            val d = today.minusDays(b.toLong())
            val md = FinanceReportLocator.readReport(financeDir, "macro-radar", d) ?: continue
            val snap = FinanceReportYaml.readJudgmentSnapshot(md)
            if (snap != null) {
                val direct = FinanceReportYaml.stringAt(snap, "liquidity_env")
                    ?: FinanceReportYaml.stringAt(snap, "liquidity_environment")
                    ?: FinanceReportYaml.mapAt(snap, "macro")?.let { FinanceReportYaml.stringAt(it, "liquidity_env") }
                if (direct != null) return direct
                // Title fallback: "宏观雷达 -- 流动性中性偏紧，边际恶化中"
                val title = (snap["title"] as? String) ?: ""
                LIQUIDITY_TITLE_RE.find(title)?.let { return it.groupValues[1].trim() }
            }
            // Markdown body fallback
            LIQUIDITY_BODY_RE.find(md)?.let { return it.groupValues[1].trim() }
        }
        return null
    }

    private val LIQUIDITY_TITLE_RE = Regex("""流动性([宽中紧][^，,。\s]{0,8})""")
    private val LIQUIDITY_BODY_RE = Regex("""流动性环境[从转为].{0,12}?([宽中紧][^，,。\s]{0,8})""")

    /**
     * Walk back up to 5 days collecting all available scenario trees:
     *   1. market-research.md primary tree (under `judgment_snapshot.thread_scenario_tree`),
     *      labeled as "[main_thread name]" — kept first so it stays the default selection.
     *   2. thread-tracker.md per-active-thread trees (under
     *      `judgment_snapshot.active_threads[].thread_scenario_tree`), labeled as
     *      "[thread] · [thread_sub]" or just "[thread]" if no sub.
     *
     * Returned map preserves insertion order (LinkedHashMap) so the ScenarioPanel
     * sees market-research first, then thread-tracker's active threads in order.
     */
    private fun readAllScenarioTrees(financeDir: Path, today: LocalDate): Map<String, ThreadScenarioTree> {
        val out = LinkedHashMap<String, ThreadScenarioTree>()
        return FinanceReportLocator.walkRecentDays(today, 0..5) { d ->
            val day = financeDir.resolve("reports").resolve(d.toString())

            // 1) market-research primary tree
            if (out.isEmpty()) {  // only take from the most-recent day that has anything
                val mr = day.resolve("market-research.md")
                if (Files.isRegularFile(mr)) {
                    parseMarketResearchTree(mr)?.let { (label, tree) -> out[label] = tree }
                }
            }

            // 2) thread-tracker per-active-thread trees
            val tt = day.resolve("thread-tracker.md")
            if (Files.isRegularFile(tt)) {
                parseThreadTrackerTrees(tt).forEach { (label, tree) -> out.putIfAbsent(label, tree) }
            }

            // 3) theme-incubator candidate themes → synthetic 2-branch trees (A 点火 / B 证伪)
            //    Labeled "🔥 <theme_name>" so the user sees these are pre-ignition watches
            //    rather than active threads.
            val ti = day.resolve("theme-incubator.md")
            if (Files.isRegularFile(ti)) {
                parseIncubatorCandidates(ti).forEach { (label, tree) -> out.putIfAbsent(label, tree) }
            }

            // 4) position-risk-monitor position_scenarios → per-position scenario trees
            //    Labeled "💼 <position name>" for "I hold this, when do I reduce / exit"
            val pr = day.resolve("position-risk-monitor.md")
            if (Files.isRegularFile(pr)) {
                parsePositionScenarios(pr).forEach { (label, tree) -> out.putIfAbsent(label, tree) }
            }
            out.takeIf { it.isNotEmpty() }
        } ?: out
    }

    private fun parseMarketResearchTree(path: Path): Pair<String, ThreadScenarioTree>? {
        val snap = FinanceReportYaml.readJudgmentSnapshot(path) ?: return null
        val parsed = FinanceScenarioTreeParser.fromYaml(snap) ?: return null
        val label = FinanceReportYaml.stringAt(snap, "main_thread")
            ?: parsed.leaderName
            ?: parsed.leaderSymbol
        return label to parsed
    }

    /**
     * Parse theme-incubator.md candidate_themes[] and convert each into a synthetic
     * 2-branch ThreadScenarioTree (A 点火 / B 证伪) so the ScenarioPanel can render
     * pre-ignition watches alongside active threads in the same selector.
     *
     * Requires `ignition_struct.leader_symbol` + `ignition_struct.leader_ref_price`
     * + `ignition_struct.value` at minimum. Candidates lacking either are skipped.
     *
     * Branch confidences derived from `confidence_emerge` (0-10 → 0.0-1.0). Missing
     * defaults to 0.5/0.5 split.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseIncubatorCandidates(path: Path): Map<String, ThreadScenarioTree> {
        val out = LinkedHashMap<String, ThreadScenarioTree>()
        try {
            val snap = FinanceReportYaml.readJudgmentSnapshot(path) ?: return out
            val candidates = snap["candidate_themes"] as? List<Any?> ?: return out
            candidates.forEach { item ->
                if (item !is Map<*, *>) return@forEach
                val m = item as Map<String, Any?>
                val themeName = (m["theme"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                val ignition = m["ignition_struct"] as? Map<String, Any?> ?: return@forEach
                val leaderSym = (ignition["leader_symbol"] as? String)?.takeIf { it.isNotBlank() } ?: return@forEach
                val leaderRef = FinanceReportYaml.doubleAt(ignition, "leader_ref_price") ?: return@forEach
                val ignVal = FinanceReportYaml.doubleAt(ignition, "value") ?: return@forEach

                val invalidation = m["invalidation_struct"] as? Map<String, Any?>
                val confidenceEmerge = FinanceReportYaml.doubleAt(m, "confidence_emerge") ?: 5.0
                val igniteProb = (confidenceEmerge / 10.0).coerceIn(0.1, 0.9)

                val branches = ArrayList<ScenarioBranch>(2)
                branches.add(
                    ScenarioBranch(
                        id = "A_点火",
                        condition = m["ignition_signal"] as? String,
                        priceTriggerType = when ((ignition["type"] as? String)?.lowercase()) {
                            "below" -> PriceTriggerType.BELOW
                            else -> PriceTriggerType.ABOVE
                        },
                        priceTriggerValue = ignVal,
                        priceTriggerLow = null,
                        priceTriggerHigh = null,
                        priceTriggerDays = null,
                        volumeYiGte = FinanceReportYaml.doubleAt(ignition, "volume_yi_gte"),
                        nextPhase = "发酵",
                        confidence = igniteProb,
                        action = ignition["action"] as? String,
                    )
                )
                if (invalidation != null) {
                    val invVal = FinanceReportYaml.doubleAt(invalidation, "value")
                    if (invVal != null && invVal > 0) {
                        branches.add(
                            ScenarioBranch(
                                id = "B_证伪",
                                condition = m["invalidation"] as? String,
                                priceTriggerType = when ((invalidation["type"] as? String)?.lowercase()) {
                                    "above" -> PriceTriggerType.ABOVE
                                    else -> PriceTriggerType.BELOW
                                },
                                priceTriggerValue = invVal,
                                priceTriggerLow = null,
                                priceTriggerHigh = null,
                                priceTriggerDays = null,
                                volumeYiGte = null,
                                nextPhase = "退潮",
                                confidence = 1.0 - igniteProb,
                                action = invalidation["action"] as? String,
                            )
                        )
                    }
                }

                val poolName = (m["candidate_pool"] as? List<Any?>)?.firstOrNull()?.let { p ->
                    (p as? Map<*, *>)?.get("name") as? String
                }
                val synthetic = ThreadScenarioTree(
                    leaderSymbol = leaderSym,
                    leaderName = poolName,
                    leaderRefPrice = leaderRef,
                    refPriceDate = null,
                    branches = branches,
                    outOfScopeUpper = ignVal * 1.5,                     // big breakout beyond ignition
                    outOfScopeLower = leaderRef * 0.6,                  // total death
                    outOfScopeNote = "候选主题已远超预期或彻底走死，下一份 theme-incubator 必重审",
                )
                out["🔥 $themeName"] = synthetic
            }
        } catch (_: Exception) {
            // fall through silently — incubator data is optional
        }
        return out
    }

    /**
     * Parse position-risk-monitor.md `position_scenarios[]`. Each entry is a full
     * scenario_tree (same schema as §4.1.5) and gets labeled "💼 <name>" so the
     * user can see per-position reduce/exit playbooks alongside thread scenarios.
     *
     * Agent is expected to only emit these for high-conviction core positions
     * (>5% portfolio weight + clear thesis), so the selector stays manageable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parsePositionScenarios(path: Path): Map<String, ThreadScenarioTree> {
        val out = LinkedHashMap<String, ThreadScenarioTree>()
        try {
            val snap = FinanceReportYaml.readJudgmentSnapshot(path) ?: return out
            val positions = snap["position_scenarios"] as? List<Any?> ?: return out
            positions.forEach { item ->
                if (item !is Map<*, *>) return@forEach
                val m = item as Map<String, Any?>
                // Each position item IS the tree (no wrapping `thread_scenario_tree` key
                // because the field name is `position_scenarios`). Wrap to satisfy the
                // shared parser API.
                val wrapped = mapOf<String, Any?>("thread_scenario_tree" to m)
                val parsed = FinanceScenarioTreeParser.fromYaml(wrapped) ?: return@forEach
                val displayName = parsed.leaderName ?: parsed.leaderSymbol
                out["💼 $displayName"] = parsed
            }
        } catch (_: Exception) {
            // fall through silently — position_scenarios is optional
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseThreadTrackerTrees(path: Path): Map<String, ThreadScenarioTree> {
        val out = LinkedHashMap<String, ThreadScenarioTree>()
        try {
            val snap = FinanceReportYaml.readJudgmentSnapshot(path) ?: return out
            val actives = snap["active_threads"] as? List<Any?> ?: return out
            actives.forEach { item ->
                if (item !is Map<*, *>) return@forEach
                val m = item as Map<String, Any?>
                val parsed = FinanceScenarioTreeParser.fromYaml(m) ?: return@forEach
                val threadName = (m["thread"] as? String)?.takeIf { it.isNotBlank() }
                val sub = (m["thread_sub"] as? String)?.takeIf { it.isNotBlank() }
                val label = when {
                    threadName != null && sub != null -> "$threadName · $sub"
                    threadName != null -> threadName
                    else -> parsed.leaderName ?: parsed.leaderSymbol
                }
                out[label] = parsed
            }
        } catch (_: Exception) {
            // fall through
        }
        return out
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
        val snap = FinanceReportYaml.readJudgmentSnapshot(mrPath)
            ?: return ThreadSnapshot(null, null, null, null, null, null)
        val mainThread = snap["main_thread"] as? String
        val phase = snap["thread_phase"] as? String
        val ageDays = FinanceReportYaml.intAt(snap, "thread_age_days")
        val healthScore = FinanceReportYaml.intAt(snap, "thread_health_score")

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
    private fun readPriorThreadSnapshot(financeDir: Path, today: LocalDate): ThreadSnapshot =
        FinanceReportLocator.walkRecentDays(today, 1..7) { d ->
            readThreadSnapshot(financeDir, d).takeIf { it.phase != null || it.mainThread != null }
        } ?: ThreadSnapshot(null, null, null, null, null, null)

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

    private fun fallbackHealth(financeDir: Path, today: LocalDate): Map<String, Health> =
        FinanceReportLocator.walkRecentDays(today, 1..5) { d ->
            val p = financeDir.resolve("reports").resolve(d.toString()).resolve("position-risk-monitor.md")
            if (!Files.isRegularFile(p)) return@walkRecentDays null
            val m = LinkedHashMap<String, Health>()
            try {
                extractHealthFromRiskReport(Files.readString(p), m)
            } catch (_: Exception) {
            }
            m.takeIf { it.isNotEmpty() }
        } ?: emptyMap()

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
        val snap = FinanceReportYaml.readJudgmentSnapshot(md) ?: return

        // 1) thesis_drift => default GREEN for everyone listed
        @Suppress("UNCHECKED_CAST")
        val drift = snap["thesis_drift"] as? List<Any?>
        drift?.forEach { item ->
            if (item is Map<*, *>) {
                @Suppress("UNCHECKED_CAST") val m = item as Map<String, Any?>
                val sym = m["symbol"]?.toString() ?: return@forEach
                val score = FinanceReportYaml.intAt(m, "drift_score") ?: 0
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
