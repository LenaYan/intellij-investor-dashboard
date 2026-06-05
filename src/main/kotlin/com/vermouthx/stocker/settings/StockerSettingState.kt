package com.vermouthx.stocker.settings

import com.vermouthx.stocker.enums.StockerQuoteColorPattern
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.enums.StockerTableColumn

class StockerSettingState {
    var version: String = ""
    var refreshInterval: Long = 5
    var quoteProvider: StockerQuoteProvider = StockerQuoteProvider.SINA
    var cryptoQuoteProvider: StockerQuoteProvider = StockerQuoteProvider.SINA
    var quoteColorPattern: StockerQuoteColorPattern = StockerQuoteColorPattern.RED_UP_GREEN_DOWN
    var displayNameWithPinyin: Boolean = false
    var languageOverride: String = "" // Empty string means follow system language
    var visibleTableColumns: MutableList<String> = mutableListOf() // Empty list will be populated with defaults on first access
    var aShareList: MutableList<String> = mutableListOf()
    var hkStocksList: MutableList<String> = mutableListOf()
    var usStocksList: MutableList<String> = mutableListOf()
    var cryptoList: MutableList<String> = mutableListOf()
    var futuresList: MutableList<String> = mutableListOf()
    // ── Unified favorites list (replaces per-market lists above) ────────────────
    // Format: "MARKET_ID:CODE" e.g. "CN:SZ000001", "HK:00700", "US:AAPL"
    var favoritesList: MutableList<String> = mutableListOf()
    var favoritesMigrated: Boolean = false
    // ─────────────────────────────────────────────────────────────────────────────
    var customStockNames: MutableMap<String, String> = mutableMapOf()
    var stockCostPrices: MutableMap<String, Double> = mutableMapOf()
    var stockHoldings: MutableMap<String, Int> = mutableMapOf()
    var focusedStocks: MutableSet<String> = mutableSetOf()

    // ── Finance Bridge (~/Claude/finance integration) ─────────────────────────
    /** Master switch. If true *and* financeBaseDir exists, Stocker reads watchlist/portfolio/reports. */
    var financeBridgeEnabled: Boolean = true
    /** Absolute path or ~-prefixed. Empty string means default ~/Claude/finance. */
    var financeBaseDir: String = ""
    /** Fire notifications when price enters target_zone low or breaks invalidation level. */
    var financeNotifyTriggers: Boolean = true
    /** Fire notifications when live price crosses entry-timing.md trigger or invalidation. */
    var financeNotifyEntryTiming: Boolean = true
    /** Show the calibration "pred vs actual" sub-tab in the Finance tool window. */
    var financeShowCalibrationTab: Boolean = true
    /** Show the entry-timing sub-tab in the Finance tool window. */
    var financeShowEntryTimingTab: Boolean = true
    /** Flash the main-thread header for ~3s when phase or leader transitions vs yesterday. */
    var financeHighlightThreadChange: Boolean = true

    // ── Cloud Sync ────────────────────────────────────────────────────────────
    var cloudSyncEnabled: Boolean = false
    var cloudSyncBaseUrl: String = "http://localhost:8080"
    var cloudSyncApiKey: String = ""
    /** Auto-sync: push changes to cloud on every add/remove. */
    var cloudSyncAutoEnabled: Boolean = false
}