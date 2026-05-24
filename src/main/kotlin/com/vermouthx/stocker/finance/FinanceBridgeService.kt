package com.vermouthx.stocker.finance

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.settings.StockerSetting
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * Application-level orchestrator gluing the finance/ working directory into Stocker.
 *
 * Responsibilities:
 *   - Resolve the configured finance/ directory (with sensible default ~/Claude/finance).
 *   - Spin up [FinanceFileWatcher] when enabled.
 *   - Expose [state] for UI components (e.g. the Health cell renderer).
 *
 * Auto-disable rule: if the configured finance/ directory does not exist, we silently
 * skip everything — users without the finance/ project never see anything new.
 */
@Service(Service.Level.APP)
class FinanceBridgeService : Disposable {

    private val log = Logger.getInstance(FinanceBridgeService::class.java)
    internal val state = FinanceState()

    private val started = AtomicBoolean(false)
    private var watcher: FinanceFileWatcher? = null
    private var notifier: FinanceNotifier? = null
    private var reportNotifier: FinanceReportNotifier? = null
    private val refreshListeners = CopyOnWriteArrayList<() -> Unit>()

    fun snapshot(): FinanceState.Snapshot = state.get()

    /**
     * Resolve the configured finance/ directory. Exposed so UI panels can read the
     * reports/&lt;today&gt; directory without re-implementing the path logic.
     */
    fun financeDir(): Path = resolveFinanceDir()

    /**
     * Register a callback fired on the Swing thread whenever the finance/ state has been
     * reloaded (file watcher detected a change). Returns the same callback so caller can
     * unregister it on dispose.
     */
    fun addRefreshListener(listener: () -> Unit): () -> Unit {
        refreshListeners.add(listener)
        return listener
    }

    fun removeRefreshListener(listener: () -> Unit) {
        refreshListeners.remove(listener)
    }

    private fun fireRefresh() {
        if (refreshListeners.isEmpty()) return
        SwingUtilities.invokeLater {
            refreshListeners.forEach { l ->
                try { l() } catch (e: Exception) { log.warn("finance refresh listener threw", e) }
            }
        }
    }

    fun healthOf(code: String?): FinanceState.Health = state.healthOf(code)

    fun watchlistEntry(code: String?): WatchlistEntry? = state.watchlistEntry(code)

    /** Project-scoped notifier; null when the bridge hasn't been started for any project. */
    internal fun getNotifier(): FinanceNotifier? = notifier

    /**
     * Group watchlist symbols into the four Stocker market buckets so [StockerApp] can
     * fold them into the per-market HTTP fetch (no duplicate requests for codes that
     * happen to live in both the user's "我的自选" and the Claude/watchlist.json).
     *
     * Returned codes are stripped of the `.SH` / `.SZ` / `.BJ` / `.HK` / `.US` suffix to
     * match the format Stocker's quote provider expects.
     */
    fun watchlistCodesByMarket(): Map<StockerMarketType, List<String>> {
        val out = HashMap<StockerMarketType, MutableList<String>>()
        snapshot().watchlistBySymbol.values.forEach { entry ->
            val market = inferMarket(entry.symbol) ?: return@forEach
            val bareCode = entry.symbol.substringBefore('.').trim()
            if (bareCode.isNotEmpty()) {
                out.getOrPut(market) { ArrayList() }.add(bareCode)
            }
        }
        return out
    }

    /** Suffix-then-shape inference: ".SH"/".SZ"/".BJ" → CN, ".HK" → HK, ".US" → US. */
    private fun inferMarket(symbol: String): StockerMarketType? {
        val suffix = symbol.substringAfter('.', missingDelimiterValue = "").uppercase()
        when (suffix) {
            "SH", "SZ", "BJ" -> return StockerMarketType.AShare
            "HK" -> return StockerMarketType.HKStocks
            "US" -> return StockerMarketType.USStocks
        }
        // Fallback: infer from code shape when the symbol has no suffix
        val bare = symbol.substringBefore('.').trim()
        return when {
            FinanceSymbol.isAShareCode(bare) -> StockerMarketType.AShare
            bare.length == 5 && bare.all { it.isDigit() } -> StockerMarketType.HKStocks
            bare.isNotEmpty() && bare.all { it.isLetter() } -> StockerMarketType.USStocks
            else -> null
        }
    }

    /**
     * Idempotent. Called by [com.vermouthx.stocker.activities.StockerStartupActivity].
     * If the user has disabled the bridge or the finance/ dir is missing, becomes a no-op
     * (and remains startable later if config changes).
     */
    fun startIfEnabled(project: Project) {
        if (!started.compareAndSet(false, true)) return
        val setting = StockerSetting.instance
        if (!setting.financeBridgeEnabled) {
            log.info("FinanceBridge disabled by settings.")
            started.set(false)
            return
        }
        val dir = resolveFinanceDir()
        if (!java.nio.file.Files.isDirectory(dir)) {
            log.info("FinanceBridge: configured dir $dir not found, bridge stays idle.")
            started.set(false)
            return
        }

        // 1. Notifier (scoped to this project so scenario-state notifications land in the
        //    right window). Anomaly / limit-hit popups (±5/±7/涨跌停) used to fire from a
        //    FinanceSignalDetector here; they were removed — those signals are surfaced
        //    inline in the DISTANCE column / scenario panel instead.
        val n = FinanceNotifier(project)
        notifier = n

        // 2.b Report-event notifier (overnight-brief / longhubang first-seen toasts)
        val rn = FinanceReportNotifier(project, state)
        reportNotifier = rn

        // 2. File watcher — reload state then notify UI panels.
        val w = FinanceFileWatcher(dir) {
            state.reload(dir)
            rn.onReload(dir)
            fireRefresh()
        }
        watcher = w
        w.start()
        // Fire once after the initial read so panels render the latest data.
        rn.onReload(dir)
        fireRefresh()

        log.info("FinanceBridge started: dir=$dir")
    }

    /** Force a reload after a settings change. Safe to call anytime. */
    fun reloadNow() {
        val dir = resolveFinanceDir()
        if (java.nio.file.Files.isDirectory(dir)) {
            state.reload(dir)
        } else {
            state.reset()
        }
        fireRefresh()
    }

    override fun dispose() {
        watcher?.stop()
        watcher = null
        notifier?.reset()
        notifier = null
        reportNotifier?.reset()
        reportNotifier = null
        state.reset()
        started.set(false)
    }

    private fun resolveFinanceDir(): Path {
        val configured = StockerSetting.instance.financeBaseDir
        val raw = if (configured.isBlank()) defaultDir() else configured
        return Paths.get(expandTilde(raw))
    }

    private fun defaultDir(): String {
        val home = System.getProperty("user.home") ?: "/"
        return "$home/Claude/finance"
    }

    private fun expandTilde(p: String): String {
        if (p.startsWith("~/") || p == "~") {
            val home = System.getProperty("user.home") ?: return p
            return home + p.substring(1)
        }
        return p
    }

    companion object {
        @JvmStatic
        val instance: FinanceBridgeService
            get() = ApplicationManager.getApplication().getService(FinanceBridgeService::class.java)
    }
}
