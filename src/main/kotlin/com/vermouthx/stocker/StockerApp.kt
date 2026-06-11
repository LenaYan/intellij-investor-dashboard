package com.vermouthx.stocker

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketIndex
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_ALL_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_ALL_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.StockerRefreshState
import com.vermouthx.stocker.listeners.StockerRefreshStatus
import com.vermouthx.stocker.listeners.StockerRefreshStatusNotifier.Companion.REFRESH_STATUS_TOPIC
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerIntradayCache
import com.vermouthx.stocker.utils.StockerMarketSession
import com.vermouthx.stocker.utils.StockerQuoteHttpUtil
import com.vermouthx.stocker.views.StockerTableView
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class StockerApp {

    private val setting = StockerSetting.instance
    private val messageBus = ApplicationManager.getApplication().messageBus

    private var scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private var intradayExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(1)

    private val intradayPeriodSeconds: Long = 60L

    @Volatile private var intradayTickCounter: Long = 0

    private var scheduleInitialDelay: Long = 3
    @Volatile
    private var refreshActive: Boolean = false

    enum class State { RUNNING, PAUSED }

    @Volatile
    private var pauseState: State = State.RUNNING
    @Volatile private var offHoursTickCounter: Long = 0
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var skipUntil: Instant = Instant.MIN
    @Volatile private var lastSuccessAt: Instant? = null

    fun pause() { pauseState = State.PAUSED }
    fun resume() { pauseState = State.RUNNING }
    fun isPaused(): Boolean = pauseState == State.PAUSED

    /** True once schedule() ran on the current executors; reset by shutdown(). */
    @Volatile
    private var scheduled = false

    @Synchronized
    fun schedule() {
        if (scheduled && !scheduledExecutorService.isShutdown) {
            // Already running — a second project joining the shared app must not
            // stack a duplicate task onto the same executor.
            return
        }
        if (scheduledExecutorService.isShutdown) {
            scheduledExecutorService = Executors.newScheduledThreadPool(1)
            scheduleInitialDelay = 0
        }
        refreshActive = true
        // Use single consolidated task instead of multiple overlapping tasks
        // This reduces HTTP requests by 50% and prevents redundant data fetching.
        // Interval is read here (not at construction) so a settings change takes
        // effect on the shutdown+schedule cycle the settings panel triggers.
        scheduledExecutorService.scheduleAtFixedRate(
            createConsolidatedUpdateThread(),
            scheduleInitialDelay,
            setting.refreshInterval.coerceIn(1L, 300L),
            TimeUnit.SECONDS
        )
        if (intradayExecutor.isShutdown) {
            intradayExecutor = Executors.newScheduledThreadPool(1)
        }
        intradayExecutor.scheduleAtFixedRate(
            createIntradayUpdateThread(),
            scheduleInitialDelay,
            intradayPeriodSeconds,
            TimeUnit.SECONDS,
        )
        scheduled = true
    }

    fun shutdown() {
        scheduled = false
        refreshActive = false
        intradayTickCounter = 0
        offHoursTickCounter = 0
        skipUntil = Instant.MIN
        intradayExecutor.shutdownNow()
        scheduledExecutorService.shutdownNow()
        StockerQuoteHttpUtil.closeConnections()
    }

    fun isShutdown(): Boolean {
        return scheduledExecutorService.isShutdown
    }

    private fun clear() {
        messageBus.syncPublisher(STOCK_ALL_QUOTE_RELOAD_TOPIC).clear()
    }

    fun shutdownThenClear() {
        shutdown()
        clear()
    }

    /**
     * Consolidated update thread that fetches all market data once and publishes to all relevant topics.
     * This eliminates redundant HTTP requests that were previously made by separate per-market tasks.
     *
     * The body is guarded as a whole: scheduleAtFixedRate cancels all future runs after the
     * first uncaught exception, and syncPublisher runs listeners synchronously on this thread,
     * so an unguarded listener bug would silently kill the refresh loop forever.
     */
    private fun createConsolidatedUpdateThread(): Runnable {
        return Runnable {
            try {
                consolidatedTick()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Logger.getInstance(StockerApp::class.java)
                    .warn("consolidated quote tick failed; refresh loop continues", e)
            }
        }
    }

    private fun consolidatedTick() {
            if (pauseState == State.PAUSED) {
                publishStatus(StockerRefreshState.PAUSED)
                return
            }
            if (!shouldContinueRefresh()) {
                return
            }

            val now = Instant.now()
            val offHours = !anyRelevantMarketOpen(now)
            if (offHours) {
                val ticksPerMinute = (60L / setting.refreshInterval.coerceAtLeast(1L)).coerceAtLeast(1L)
                val skipThisTick = offHoursTickCounter % ticksPerMinute != 0L
                offHoursTickCounter++
                if (skipThisTick) {
                    publishStatus(StockerRefreshState.OFF_HOURS)
                    return
                }
            } else {
                offHoursTickCounter = 0
            }

            if (now.isBefore(skipUntil)) {
                publishStatus(StockerRefreshState.BACKOFF)
                return
            }

            val quoteProvider = setting.quoteProvider
            val cryptoQuoteProvider = setting.cryptoQuoteProvider

            // Get per-market codes from unified favorites list
            val watchlistByMarket = FinanceBridgeService.instance.watchlistCodesByMarket()
            val aShareCodes = unionCodes(setting.codesByMarket(StockerMarketType.AShare), watchlistByMarket[StockerMarketType.AShare])
            val hkCodes     = unionCodes(setting.codesByMarket(StockerMarketType.HKStocks), watchlistByMarket[StockerMarketType.HKStocks])
            val usCodes     = unionCodes(setting.codesByMarket(StockerMarketType.USStocks), watchlistByMarket[StockerMarketType.USStocks])
            val cryptoCodes = setting.codesByMarket(StockerMarketType.Crypto)
            val futuresCodes = setting.codesByMarket(StockerMarketType.Futures)

            // Fetch all market data
            val aShareResult     = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        aShareCodes)  ?: return
            val hkStocksResult   = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        hkCodes)      ?: return
            val usStocksResult   = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        usCodes)      ?: return
            val cryptoResult     = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  cryptoCodes)  ?: return
            val futuresResult    = fetchQuotesIfActive(StockerMarketType.Futures,  StockerQuoteProvider.SINA, futuresCodes) ?: return

            val aShareIdxResult  = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        StockerMarketIndex.CN.codes)     ?: return
            val hkStocksIdxResult= fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        StockerMarketIndex.HK.codes)     ?: return
            val usStocksIdxResult= fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        StockerMarketIndex.US.codes)     ?: return
            val cryptoIdxResult  = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  StockerMarketIndex.Crypto.codes) ?: return

            val allResults = listOf(
                aShareResult, hkStocksResult, usStocksResult, cryptoResult, futuresResult,
                aShareIdxResult, hkStocksIdxResult, usStocksIdxResult, cryptoIdxResult,
            )
            val anyFailure = allResults.any { it.isFailure }

            val aShareQuotes    = aShareResult.getOrDefault(emptyList())
            val hkStocksQuotes  = hkStocksResult.getOrDefault(emptyList())
            val usStocksQuotes  = usStocksResult.getOrDefault(emptyList())
            val cryptoQuotes    = cryptoResult.getOrDefault(emptyList())
            val futuresQuotes   = futuresResult.getOrDefault(emptyList())
            val aShareIndices   = aShareIdxResult.getOrDefault(emptyList())
            val hkStocksIndices = hkStocksIdxResult.getOrDefault(emptyList())
            val usStocksIndices = usStocksIdxResult.getOrDefault(emptyList())
            val cryptoIndices   = cryptoIdxResult.getOrDefault(emptyList())

            if (!shouldContinueRefresh()) {
                return
            }

            // Publish to ALL topic only — per-market topics are removed
            val allStockQuotes = listOf(aShareQuotes, hkStocksQuotes, usStocksQuotes, cryptoQuotes, futuresQuotes).flatten()
            val allStockIndices = listOf(aShareIndices, hkStocksIndices, usStocksIndices, cryptoIndices).flatten()
            val allPublisher = messageBus.syncPublisher(STOCK_ALL_QUOTE_UPDATE_TOPIC)
            allPublisher.syncQuotes(allStockQuotes, allStockQuotes.size)
            allPublisher.syncIndices(allStockIndices)

            if (anyFailure) {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    val baseSec = setting.refreshInterval.coerceAtLeast(1L)
                    val shift = (consecutiveFailures - 2).coerceAtMost(8)
                    val delaySec = minOf(300L, baseSec * (1L shl shift))
                    skipUntil = now.plusSeconds(delaySec)
                    val log = Logger.getInstance(StockerApp::class.java)
                    log.warn("quote backoff: failures=$consecutiveFailures, next=${delaySec}s")
                }
                publishStatus(if (consecutiveFailures >= 3) StockerRefreshState.BACKOFF
                              else if (offHours) StockerRefreshState.OFF_HOURS
                              else StockerRefreshState.LIVE)
            } else {
                if (consecutiveFailures > 0) {
                    val log = Logger.getInstance(StockerApp::class.java)
                    log.info("quote recovered after $consecutiveFailures failures")
                    consecutiveFailures = 0
                    skipUntil = Instant.MIN
                }
                lastSuccessAt = Instant.now()
                publishStatus(if (offHours) StockerRefreshState.OFF_HOURS else StockerRefreshState.LIVE)
            }
    }

    private fun publishStatus(state: StockerRefreshState) {
        try {
            messageBus.syncPublisher(REFRESH_STATUS_TOPIC)
                .statusChanged(StockerRefreshStatus(state, setting.refreshInterval, lastSuccessAt))
        } catch (e: Exception) {
            Logger.getInstance(StockerApp::class.java).warn("status publish failed", e)
        }
    }

    /**
     * Independent 60s intraday update: viewport + cache aware. Skipped entirely
     * when paused or when no relevant market is open. Guarded like the
     * consolidated tick: an uncaught exception would cancel the schedule.
     */
    private fun createIntradayUpdateThread(): Runnable {
        return Runnable {
            try {
                intradayTick()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Logger.getInstance(StockerApp::class.java)
                    .warn("intraday tick failed; refresh loop continues", e)
            }
        }
    }

    private fun intradayTick() {
            if (pauseState == State.PAUSED) return
            if (!shouldContinueRefresh()) return

            val now = Instant.now()
            if (!anyRelevantMarketOpen(now)) return

            val visible = StockerTableView.visibleCodesByMarket()
            if (visible.isEmpty()) return

            val toBroadcast = HashMap<String, com.vermouthx.stocker.entities.StockerIntradayData>()
            val intradayMarkets = setOf(
                StockerMarketType.AShare,
                StockerMarketType.HKStocks,
                StockerMarketType.USStocks,
            )

            for ((market, codes) in visible) {
                if (market !in intradayMarkets) continue
                // Per-market gate: anyRelevantMarketOpen above may be true because of a
                // different market (e.g. US evening session); minute data for a closed
                // market is static, so skip the fetch for it.
                if (StockerMarketSession.of(market)?.isOpen(now) != true) continue
                if (!shouldContinueRefresh()) return

                val hits = HashMap<String, com.vermouthx.stocker.entities.StockerIntradayData>()
                val miss = ArrayList<String>()
                for (code in codes) {
                    val cached = StockerIntradayCache.get(code, now)
                    if (cached != null) hits[code] = cached else miss.add(code)
                }

                if (miss.isNotEmpty()) {
                    val fetched = StockerQuoteHttpUtil.getIntradayData(market, miss)
                    for ((code, data) in fetched) {
                        StockerIntradayCache.put(code, data, now)
                        toBroadcast[code] = data
                    }
                }
                toBroadcast.putAll(hits)
            }

            if (toBroadcast.isNotEmpty()) {
                StockerTableView.syncAllIntradayData(toBroadcast)
            }

            intradayTickCounter++
            if (intradayTickCounter % 10L == 0L) {
                val keep = HashSet<String>()
                visible.values.forEach { keep.addAll(it) }
                keep.addAll(setting.codesByMarket(StockerMarketType.AShare))
                keep.addAll(setting.codesByMarket(StockerMarketType.HKStocks))
                keep.addAll(setting.codesByMarket(StockerMarketType.USStocks))
                StockerIntradayCache.evict(keep)
            }
    }

    /**
     * Returns true when at least one market the user has codes in is currently open,
     * or when one of the index markets whose quotes every tab displays (CN/HK/US
     * dropdown indices) is open. Codes are taken from the unified favourites list plus
     * the finance/ watchlist additions — same union the consolidated task already builds.
     *
     * The Crypto index (BTC, 24/7) is deliberately excluded from the index check:
     * counting it would keep this method permanently true and disable the off-hours
     * throttle. Users who actually hold crypto codes still get full cadence via the
     * codes loop.
     */
    private fun anyRelevantMarketOpen(now: Instant): Boolean {
        val watchlistByMarket = FinanceBridgeService.instance.watchlistCodesByMarket()
        for (market in StockerMarketType.entries) {
            val codes = setting.codesByMarket(market) +
                        (watchlistByMarket[market] ?: emptyList())
            if (codes.isEmpty()) continue
            val session = StockerMarketSession.of(market) ?: continue
            if (session.isOpen(now)) return true
        }
        val indexMarkets = listOf(
            StockerMarketType.AShare,
            StockerMarketType.HKStocks,
            StockerMarketType.USStocks,
        )
        return indexMarkets.any { StockerMarketSession.of(it)?.isOpen(now) == true }
    }

    private fun fetchQuotesIfActive(
        marketType: StockerMarketType,
        quoteProvider: StockerQuoteProvider,
        codes: List<String>
    ): Result<List<StockerQuote>>? {
        if (!shouldContinueRefresh()) {
            return null
        }
        return StockerQuoteHttpUtil.get(marketType, quoteProvider, codes)
    }

    private fun shouldContinueRefresh(): Boolean {
        return refreshActive && !Thread.currentThread().isInterrupted
    }

    /** Combine the user's persisted favourites with the watchlist additions; preserves
     *  favourite order, appends only new watchlist codes at the end. */
    private fun unionCodes(favourites: List<String>, watchlist: List<String>?): List<String> {
        if (watchlist.isNullOrEmpty()) return favourites
        val seen = HashSet<String>(favourites.size + watchlist.size)
        seen.addAll(favourites)
        val out = ArrayList<String>(favourites.size + watchlist.size)
        out.addAll(favourites)
        for (c in watchlist) {
            if (c.isNotBlank() && seen.add(c)) out.add(c)
        }
        return out
    }

}
