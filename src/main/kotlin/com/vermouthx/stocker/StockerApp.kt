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
    private val schedulePeriod: Long = StockerSetting.instance.refreshInterval
    @Volatile
    private var refreshActive: Boolean = false

    enum class State { RUNNING, PAUSED }

    @Volatile
    private var pauseState: State = State.RUNNING
    @Volatile private var offHoursTickCounter: Long = 0
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var skipUntil: Instant = Instant.MIN

    fun pause() { pauseState = State.PAUSED }
    fun resume() { pauseState = State.RUNNING }
    fun isPaused(): Boolean = pauseState == State.PAUSED

    fun schedule() {
        if (scheduledExecutorService.isShutdown) {
            scheduledExecutorService = Executors.newScheduledThreadPool(1)
            scheduleInitialDelay = 0
        }
        refreshActive = true
        // Use single consolidated task instead of multiple overlapping tasks
        // This reduces HTTP requests by 50% and prevents redundant data fetching
        scheduledExecutorService.scheduleAtFixedRate(
            createConsolidatedUpdateThread(),
            scheduleInitialDelay,
            schedulePeriod,
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
    }

    fun shutdown() {
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
     */
    private fun createConsolidatedUpdateThread(): Runnable {
        return Runnable {
            if (pauseState == State.PAUSED) {
                return@Runnable
            }
            if (!shouldContinueRefresh()) {
                return@Runnable
            }

            val now = Instant.now()
            if (!anyRelevantMarketOpen(now)) {
                val ticksPerMinute = (60L / setting.refreshInterval.coerceAtLeast(1L)).coerceAtLeast(1L)
                val skipThisTick = offHoursTickCounter % ticksPerMinute != 0L
                offHoursTickCounter++
                if (skipThisTick) {
                    return@Runnable
                }
            } else {
                offHoursTickCounter = 0
            }

            if (now.isBefore(skipUntil)) {
                return@Runnable
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
            val aShareResult     = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        aShareCodes)  ?: return@Runnable
            val hkStocksResult   = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        hkCodes)      ?: return@Runnable
            val usStocksResult   = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        usCodes)      ?: return@Runnable
            val cryptoResult     = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  cryptoCodes)  ?: return@Runnable
            val futuresResult    = fetchQuotesIfActive(StockerMarketType.Futures,  StockerQuoteProvider.SINA, futuresCodes) ?: return@Runnable

            val aShareIdxResult  = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        StockerMarketIndex.CN.codes)     ?: return@Runnable
            val hkStocksIdxResult= fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        StockerMarketIndex.HK.codes)     ?: return@Runnable
            val usStocksIdxResult= fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        StockerMarketIndex.US.codes)     ?: return@Runnable
            val cryptoIdxResult  = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  StockerMarketIndex.Crypto.codes) ?: return@Runnable

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
                return@Runnable
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
            } else if (consecutiveFailures > 0) {
                val log = Logger.getInstance(StockerApp::class.java)
                log.info("quote recovered after $consecutiveFailures failures")
                consecutiveFailures = 0
                skipUntil = Instant.MIN
            }

        }
    }

    /**
     * Independent 60s intraday update: viewport + cache aware. Skipped entirely
     * when paused or when no relevant market is open.
     */
    private fun createIntradayUpdateThread(): Runnable {
        return Runnable {
            if (pauseState == State.PAUSED) return@Runnable
            if (!shouldContinueRefresh()) return@Runnable

            val now = Instant.now()
            if (!anyRelevantMarketOpen(now)) return@Runnable

            val visible = StockerTableView.visibleCodesByMarket()
            if (visible.isEmpty()) return@Runnable

            val toBroadcast = HashMap<String, com.vermouthx.stocker.entities.StockerIntradayData>()
            val intradayMarkets = setOf(
                StockerMarketType.AShare,
                StockerMarketType.HKStocks,
                StockerMarketType.USStocks,
            )

            for ((market, codes) in visible) {
                if (market !in intradayMarkets) continue
                if (!shouldContinueRefresh()) return@Runnable

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
    }

    /**
     * Returns true when at least one market the user has codes in is currently open.
     * Codes are taken from the unified favourites list plus the finance/ watchlist
     * additions — same union the consolidated task already builds.
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
        return false
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
