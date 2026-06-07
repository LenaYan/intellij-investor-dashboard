package com.vermouthx.stocker

import com.intellij.openapi.application.ApplicationManager
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketIndex
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_ALL_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_ALL_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.settings.StockerSetting
import com.vermouthx.stocker.utils.StockerQuoteHttpUtil
import com.vermouthx.stocker.views.StockerTableView
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class StockerApp {

    private val setting = StockerSetting.instance
    private val messageBus = ApplicationManager.getApplication().messageBus

    private var scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)

    private var scheduleInitialDelay: Long = 3
    private val schedulePeriod: Long = StockerSetting.instance.refreshInterval
    @Volatile
    private var refreshActive: Boolean = false

    enum class State { RUNNING, PAUSED }

    @Volatile
    private var pauseState: State = State.RUNNING
    @Volatile private var offHoursTickCounter: Long = 0

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
    }

    fun shutdown() {
        refreshActive = false
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

            val now = java.time.Instant.now()
            if (!anyRelevantMarketOpen(now)) {
                offHoursTickCounter++
                val ticksPerMinute = (60L / setting.refreshInterval.coerceAtLeast(1L)).coerceAtLeast(1L)
                if (offHoursTickCounter % ticksPerMinute != 0L) {
                    return@Runnable
                }
            } else {
                offHoursTickCounter = 0
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
            val aShareQuotes = fetchQuotesIfActive(StockerMarketType.AShare, quoteProvider, aShareCodes) ?: return@Runnable
            val hkStocksQuotes = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider, hkCodes) ?: return@Runnable
            val usStocksQuotes = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider, usCodes) ?: return@Runnable
            val cryptoQuotes = fetchQuotesIfActive(StockerMarketType.Crypto, cryptoQuoteProvider, cryptoCodes) ?: return@Runnable
            val futuresQuotes = fetchQuotesIfActive(StockerMarketType.Futures, StockerQuoteProvider.SINA, futuresCodes) ?: return@Runnable

            val aShareIndices = fetchQuotesIfActive(StockerMarketType.AShare, quoteProvider, StockerMarketIndex.CN.codes) ?: return@Runnable
            val hkStocksIndices = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider, StockerMarketIndex.HK.codes) ?: return@Runnable
            val usStocksIndices = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider, StockerMarketIndex.US.codes) ?: return@Runnable
            val cryptoIndices = fetchQuotesIfActive(StockerMarketType.Crypto, cryptoQuoteProvider, StockerMarketIndex.Crypto.codes) ?: return@Runnable

            if (!shouldContinueRefresh()) {
                return@Runnable
            }

            // Publish to ALL topic only — per-market topics are removed
            val allStockQuotes = listOf(aShareQuotes, hkStocksQuotes, usStocksQuotes, cryptoQuotes, futuresQuotes).flatten()
            val allStockIndices = listOf(aShareIndices, hkStocksIndices, usStocksIndices, cryptoIndices).flatten()
            val allPublisher = messageBus.syncPublisher(STOCK_ALL_QUOTE_UPDATE_TOPIC)
            allPublisher.syncQuotes(allStockQuotes, allStockQuotes.size)
            allPublisher.syncIndices(allStockIndices)

            // Fetch intraday data for sparkline display
            if (!shouldContinueRefresh()) return@Runnable
            val intradayMap = mutableMapOf<String, com.vermouthx.stocker.entities.StockerIntradayData>()
            if (aShareCodes.isNotEmpty()) {
                intradayMap.putAll(StockerQuoteHttpUtil.getIntradayData(StockerMarketType.AShare, aShareCodes))
            }
            if (!shouldContinueRefresh()) return@Runnable
            if (hkCodes.isNotEmpty()) {
                intradayMap.putAll(StockerQuoteHttpUtil.getIntradayData(StockerMarketType.HKStocks, hkCodes))
            }
            if (!shouldContinueRefresh()) return@Runnable
            if (usCodes.isNotEmpty()) {
                intradayMap.putAll(StockerQuoteHttpUtil.getIntradayData(StockerMarketType.USStocks, usCodes))
            }
            if (intradayMap.isNotEmpty()) {
                StockerTableView.syncAllIntradayData(intradayMap)
            }
        }
    }

    /**
     * Returns true when at least one market the user has codes in is currently open.
     * Codes are taken from the unified favourites list plus the finance/ watchlist
     * additions — same union the consolidated task already builds.
     */
    private fun anyRelevantMarketOpen(now: java.time.Instant): Boolean {
        val watchlistByMarket = com.vermouthx.stocker.finance.FinanceBridgeService.instance.watchlistCodesByMarket()
        for (market in com.vermouthx.stocker.enums.StockerMarketType.entries) {
            val codes = setting.codesByMarket(market) +
                        (watchlistByMarket[market] ?: emptyList())
            if (codes.isEmpty()) continue
            val session = com.vermouthx.stocker.utils.StockerMarketSession.of(market) ?: continue
            if (session.isOpen(now)) return true
        }
        return false
    }

    private fun fetchQuotesIfActive(
        marketType: StockerMarketType,
        quoteProvider: StockerQuoteProvider,
        codes: List<String>
    ): List<StockerQuote>? {
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
