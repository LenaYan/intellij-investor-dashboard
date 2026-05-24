package com.vermouthx.stocker

import com.intellij.openapi.application.ApplicationManager
import com.vermouthx.stocker.entities.StockerQuote
import com.vermouthx.stocker.enums.StockerMarketIndex
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.finance.FinanceBridgeService
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_ALL_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_CN_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_CRYPTO_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_HK_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_US_QUOTE_RELOAD_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.CRYPTO_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_ALL_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_CN_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_HK_QUOTE_UPDATE_TOPIC
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.STOCK_US_QUOTE_UPDATE_TOPIC
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
        messageBus.syncPublisher(STOCK_CN_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_HK_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_US_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_CRYPTO_QUOTE_RELOAD_TOPIC).clear()
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
            if (!shouldContinueRefresh()) {
                return@Runnable
            }

            val quoteProvider = setting.quoteProvider
            val cryptoQuoteProvider = setting.cryptoQuoteProvider

            // Watchlist codes from ~/Claude/finance/watchlist.json get folded into the
            // per-market fetch so we don't issue duplicate HTTP requests for codes that
            // happen to live in both setting.xxxList and the watchlist. The existing
            // CN / HK / US tabs still only display rows that are present in setting.xxxList
            // (the size guard in StockerQuoteUpdateListener caps addRow); the new
            // Watchlist tab uses its own filtering listener.
            val watchlistByMarket = FinanceBridgeService.instance.watchlistCodesByMarket()
            val aShareCodes = unionCodes(setting.aShareList, watchlistByMarket[StockerMarketType.AShare])
            val hkCodes     = unionCodes(setting.hkStocksList, watchlistByMarket[StockerMarketType.HKStocks])
            val usCodes     = unionCodes(setting.usStocksList, watchlistByMarket[StockerMarketType.USStocks])

            // Fetch all market data once
            val aShareQuotes = fetchQuotesIfActive(StockerMarketType.AShare, quoteProvider, aShareCodes) ?: return@Runnable
            val hkStocksQuotes = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider, hkCodes) ?: return@Runnable
            val usStocksQuotes = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider, usCodes) ?: return@Runnable
            val cryptoQuotes = fetchQuotesIfActive(StockerMarketType.Crypto, cryptoQuoteProvider, setting.cryptoList) ?: return@Runnable

            val aShareIndices = fetchQuotesIfActive(StockerMarketType.AShare, quoteProvider, StockerMarketIndex.CN.codes) ?: return@Runnable
            val hkStocksIndices = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider, StockerMarketIndex.HK.codes) ?: return@Runnable
            val usStocksIndices = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider, StockerMarketIndex.US.codes) ?: return@Runnable
            val cryptoIndices = fetchQuotesIfActive(StockerMarketType.Crypto, cryptoQuoteProvider, StockerMarketIndex.Crypto.codes) ?: return@Runnable

            if (!shouldContinueRefresh()) {
                return@Runnable
            }

            // Publish to individual market topics
            // Always publish indices, but only publish quotes when there are favorites
            val cnPublisher = messageBus.syncPublisher(STOCK_CN_QUOTE_UPDATE_TOPIC)
            if (setting.aShareList.isNotEmpty()) {
                cnPublisher.syncQuotes(aShareQuotes, setting.aShareList.size)
            }
            cnPublisher.syncIndices(aShareIndices)
            
            val hkPublisher = messageBus.syncPublisher(STOCK_HK_QUOTE_UPDATE_TOPIC)
            if (setting.hkStocksList.isNotEmpty()) {
                hkPublisher.syncQuotes(hkStocksQuotes, setting.hkStocksList.size)
            }
            hkPublisher.syncIndices(hkStocksIndices)
            
            val usPublisher = messageBus.syncPublisher(STOCK_US_QUOTE_UPDATE_TOPIC)
            if (setting.usStocksList.isNotEmpty()) {
                usPublisher.syncQuotes(usStocksQuotes, setting.usStocksList.size)
            }
            usPublisher.syncIndices(usStocksIndices)
            
            val cryptoPublisher = messageBus.syncPublisher(CRYPTO_QUOTE_UPDATE_TOPIC)
            if (setting.cryptoList.isNotEmpty()) {
                cryptoPublisher.syncQuotes(cryptoQuotes, setting.cryptoList.size)
            }
            cryptoPublisher.syncIndices(cryptoIndices)
            
            // Publish to "all" topic
            // Cap = allStockQuotes.size (favourites ∪ watchlist) so the addRow guard in
            // StockerQuoteUpdateListener (`quotes.size() <= size`) doesn't reject every
            // row when the watchlist adds codes that aren't in the per-market favourites.
            val allStockQuotes = listOf(aShareQuotes, hkStocksQuotes, usStocksQuotes, cryptoQuotes).flatten()
            val allStockIndices = listOf(aShareIndices, hkStocksIndices, usStocksIndices, cryptoIndices).flatten()
            val allPublisher = messageBus.syncPublisher(STOCK_ALL_QUOTE_UPDATE_TOPIC)
            allPublisher.syncQuotes(allStockQuotes, allStockQuotes.size)
            allPublisher.syncIndices(allStockIndices)

            // Fetch intraday data for sparkline display (favourites + watchlist union)
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
