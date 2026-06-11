package com.vermouthx.stocker.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteColorPattern
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.enums.StockerTableColumn
import com.vermouthx.stocker.utils.StockerPinyinUtil
import com.vermouthx.stocker.utils.StockerQuoteHttpUtil

@State(name = "Stocker", storages = [Storage("stocker-config.xml")])
class StockerSetting : PersistentStateComponent<StockerSettingState> {
    private var myState = StockerSettingState()

    private val log = Logger.getInstance(javaClass)

    companion object {
        val instance: StockerSetting
            get() = ApplicationManager.getApplication().getService(StockerSetting::class.java)
    }

    var version: String
        get() = myState.version
        set(value) {
            myState.version = value
            log.info("Stocker updated to $value")
        }

    var quoteProvider: StockerQuoteProvider
        get() = myState.quoteProvider
        set(value) {
            myState.quoteProvider = value
            log.info("Stocker stock quote provider switched to ${value.title}")
        }

    var cryptoQuoteProvider: StockerQuoteProvider
        get() = myState.cryptoQuoteProvider
        set(value) {
            myState.cryptoQuoteProvider = value
            log.info("Stocker crypto quote provider switched to ${value.title}")
        }

    var quoteColorPattern: StockerQuoteColorPattern
        get() = myState.quoteColorPattern
        set(value) {
            myState.quoteColorPattern = value
            log.info("Stocker quote color pattern switched to ${value.title}")
        }

    var displayNameWithPinyin: Boolean
        get() = myState.displayNameWithPinyin
        set(value) {
            myState.displayNameWithPinyin = value
            log.info("Stocker display name with pinyin set to $value")
        }

    var languageOverride: String
        get() = myState.languageOverride
        set(value) {
            myState.languageOverride = value
            log.info("Stocker language override set to $value")
        }

    var visibleTableColumns: List<String>
        get() {
            val stored = myState.visibleTableColumns
            if (stored.isEmpty()) return StockerTableColumn.defaultVisibleNames()
            if (stored.all { name -> StockerTableColumn.fromName(name) != null }) return stored
            val migrated = stored.mapNotNull { StockerTableColumn.migrateLocalizedTitle(it) }
            if (migrated.isNotEmpty()) {
                myState.visibleTableColumns = migrated.toMutableList()
                log.info("Migrated visibleTableColumns from localized titles to enum names: $migrated")
                return migrated
            }
            return StockerTableColumn.defaultVisibleNames()
        }
        set(value) {
            myState.visibleTableColumns = value.toMutableList()
            log.info("Stocker visible table columns updated: $value")
        }

    var refreshInterval: Long
        get() = myState.refreshInterval
        set(value) {
            myState.refreshInterval = value
            log.info("Stocker refresh interval set to $value")
        }

    // ── Unified Favorites API ─────────────────────────────────────────────────

    var favoritesList: MutableList<String>
        get() = myState.favoritesList
        set(value) {
            // The management dialog commits deletions by rebuilding this list wholesale
            // (it never calls removeFavorite), so the orphan cascade must run here too.
            val oldCodes = myState.favoritesList.mapNotNull { parseFavoriteKey(it)?.second }
            myState.favoritesList = value
            val newCodes = value.mapNotNull { parseFavoriteKey(it)?.second }.toSet()
            for (code in oldCodes) {
                if (code !in newCodes) clearCodeAttachments(code)
            }
        }

    /** Build the persisted key for a favorite entry. */
    fun favoriteKey(market: StockerMarketType, code: String): String =
        "${market.persistedId}:$code"

    /** Parse a persisted key back to (market, code). Returns null if malformed. */
    fun parseFavoriteKey(key: String): Pair<StockerMarketType, String>? {
        val colonIdx = key.indexOf(':')
        if (colonIdx <= 0 || colonIdx >= key.length - 1) return null
        val marketId = key.substring(0, colonIdx)
        val code = key.substring(colonIdx + 1)
        val market = StockerMarketType.fromPersistedId(marketId) ?: return null
        return market to code
    }

    fun addFavorite(market: StockerMarketType, code: String): Boolean {
        val key = favoriteKey(market, code)
        if (myState.favoritesList.contains(key)) return false
        return myState.favoritesList.add(key)
    }

    fun removeFavorite(market: StockerMarketType, code: String) {
        val key = favoriteKey(market, code)
        myState.favoritesList.remove(key)
        // Cascade: per-code attachments would otherwise pile up as orphans in
        // stocker-config.xml. Only when the code left its last market — the same bare
        // code may legitimately exist under another market.
        if (!containsCode(code)) {
            clearCodeAttachments(code)
        }
    }

    private fun clearCodeAttachments(code: String) {
        myState.customStockNames.remove(code)
        myState.stockCostPrices.remove(code)
        myState.stockHoldings.remove(code)
        myState.stockAlertsAbove.remove(code)
        myState.stockAlertsBelow.remove(code)
        myState.focusedStocks.remove(code)
    }

    /** Check if a code exists in favorites for a specific market. */
    fun containsFavorite(market: StockerMarketType, code: String): Boolean =
        myState.favoritesList.contains(favoriteKey(market, code))

    /** Check if a bare code exists in any market's favorites. */
    fun containsCode(code: String): Boolean =
        StockerMarketType.entries.any { containsFavorite(it, code) }

    /** Find which market a bare code belongs to in favorites. */
    fun marketOf(code: String): StockerMarketType? =
        StockerMarketType.entries.firstOrNull { containsFavorite(it, code) }

    /** Get all bare codes for a specific market (for HTTP fetching). */
    fun codesByMarket(market: StockerMarketType): List<String> {
        val prefix = "${market.persistedId}:"
        return myState.favoritesList
            .filter { it.startsWith(prefix) }
            .map { it.substring(prefix.length) }
    }

    val allStockListSize: Int
        get() = myState.favoritesList.size

    fun removeCode(market: StockerMarketType, code: String) {
        removeFavorite(market, code)
    }

    // Legacy per-market accessors (aShareList, hkStocksList, …) were removed: nothing
    // outside this class read them since the unified favoritesList migration. The raw
    // fields remain in StockerSettingState only so migrateLegacyListsIfNeeded() can
    // still deserialize pre-migration XML.

    var customStockNames: MutableMap<String, String>
        get() = myState.customStockNames
        set(value) {
            myState.customStockNames = value
        }

    var stockCostPrices: MutableMap<String, Double>
        get() = myState.stockCostPrices
        set(value) {
            myState.stockCostPrices = value
        }

    var stockHoldings: MutableMap<String, Int>
        get() = myState.stockHoldings
        set(value) {
            myState.stockHoldings = value
        }

    var financeBridgeEnabled: Boolean
        get() = myState.financeBridgeEnabled
        set(value) {
            myState.financeBridgeEnabled = value
            log.info("Stocker finance bridge enabled set to $value")
        }

    var financeBaseDir: String
        get() = myState.financeBaseDir
        set(value) {
            myState.financeBaseDir = value
            log.info("Stocker finance base dir set to $value")
        }

    var financeNotifyTriggers: Boolean
        get() = myState.financeNotifyTriggers
        set(value) {
            myState.financeNotifyTriggers = value
        }

    var financeNotifyEntryTiming: Boolean
        get() = myState.financeNotifyEntryTiming
        set(value) {
            myState.financeNotifyEntryTiming = value
        }

    var financeShowCalibrationTab: Boolean
        get() = myState.financeShowCalibrationTab
        set(value) {
            myState.financeShowCalibrationTab = value
        }

    var financeShowEntryTimingTab: Boolean
        get() = myState.financeShowEntryTimingTab
        set(value) {
            myState.financeShowEntryTimingTab = value
        }

    var financeHighlightThreadChange: Boolean
        get() = myState.financeHighlightThreadChange
        set(value) {
            myState.financeHighlightThreadChange = value
        }

    // ── Cloud Sync ──────────────────────────────────────────────────────────────

    var cloudSyncEnabled: Boolean
        get() = myState.cloudSyncEnabled
        set(value) {
            myState.cloudSyncEnabled = value
        }

    var cloudSyncBaseUrl: String
        get() = myState.cloudSyncBaseUrl
        set(value) {
            myState.cloudSyncBaseUrl = value
        }

    /**
     * Stored in the OS keychain via PasswordSafe, not in stocker-config.xml.
     * A key persisted as plaintext by older versions is migrated on first read
     * and blanked from the XML state.
     */
    var cloudSyncApiKey: String
        get() {
            migrateLegacyApiKeyIfNeeded()
            return PasswordSafe.instance.getPassword(cloudSyncCredentialAttributes()) ?: ""
        }
        set(value) {
            PasswordSafe.instance.setPassword(cloudSyncCredentialAttributes(), value.ifBlank { null })
            myState.cloudSyncApiKey = ""
        }

    private fun migrateLegacyApiKeyIfNeeded() {
        val legacy = myState.cloudSyncApiKey
        if (legacy.isBlank()) return
        if (PasswordSafe.instance.getPassword(cloudSyncCredentialAttributes()).isNullOrBlank()) {
            PasswordSafe.instance.setPassword(cloudSyncCredentialAttributes(), legacy)
            log.info("Migrated cloud sync API key from plaintext settings to PasswordSafe")
        }
        myState.cloudSyncApiKey = ""
    }

    private fun cloudSyncCredentialAttributes() = CredentialAttributes(
        generateServiceName("Stocker", "cloudSyncApiKey")
    )

    var cloudSyncAutoEnabled: Boolean
        get() = myState.cloudSyncAutoEnabled
        set(value) {
            myState.cloudSyncAutoEnabled = value
        }

    // ────────────────────────────────────────────────────────────────────────────

    // ── Price alerts (one-shot) ─────────────────────────────────────────────────

    fun getAlertAbove(code: String): Double? = myState.stockAlertsAbove[code]

    fun getAlertBelow(code: String): Double? = myState.stockAlertsBelow[code]

    fun hasAnyPriceAlert(): Boolean =
        myState.stockAlertsAbove.isNotEmpty() || myState.stockAlertsBelow.isNotEmpty()

    /** Null clears the corresponding threshold. */
    fun setPriceAlerts(code: String, above: Double?, below: Double?) {
        if (above != null) myState.stockAlertsAbove[code] = above else myState.stockAlertsAbove.remove(code)
        if (below != null) myState.stockAlertsBelow[code] = below else myState.stockAlertsBelow.remove(code)
        log.info("Price alerts for $code: above=$above below=$below")
    }

    fun clearAlertAbove(code: String) {
        myState.stockAlertsAbove.remove(code)
    }

    fun clearAlertBelow(code: String) {
        myState.stockAlertsBelow.remove(code)
    }

    // ────────────────────────────────────────────────────────────────────────────

    fun setCustomName(code: String, customName: String) {
        customStockNames[code] = customName
        log.info("Custom name set for $code: $customName")
    }

    fun getCustomName(code: String): String? {
        return customStockNames[code]
    }

    fun removeCustomName(code: String) {
        customStockNames.remove(code)
        log.info("Custom name removed for $code")
    }

    fun setCostPrice(code: String, costPrice: Double) {
        val rounded = Math.round(costPrice * 1000.0) / 1000.0
        stockCostPrices[code] = rounded
        log.info("Cost price set for $code: $rounded")
    }

    fun getCostPrice(code: String): Double? {
        return stockCostPrices[code]
    }

    fun removeCostPrice(code: String) {
        stockCostPrices.remove(code)
        log.info("Cost price removed for $code")
    }

    fun setHoldings(code: String, holdings: Int) {
        stockHoldings[code] = holdings
        log.info("Holdings set for $code: $holdings")
    }

    fun getHoldings(code: String): Int? {
        return stockHoldings[code]
    }

    fun removeHoldings(code: String) {
        stockHoldings.remove(code)
        log.info("Holdings removed for $code")
    }

    fun isStockFocused(code: String): Boolean {
        return myState.focusedStocks.contains(code)
    }

    fun toggleFocusStock(code: String): Boolean {
        val focused = if (myState.focusedStocks.contains(code)) {
            myState.focusedStocks.remove(code)
            false
        } else {
            myState.focusedStocks.add(code)
            true
        }
        log.info("Stock focus toggled for $code: $focused")
        return focused
    }

    fun getDisplayName(code: String, originalName: String): String {
        // Priority: Custom name > Pinyin mode > Original name
        customStockNames[code]?.let { return it }
        if (displayNameWithPinyin) {
            return StockerPinyinUtil.toPinyin(originalName)
        }
        return originalName
    }

    fun isTableColumnVisible(column: StockerTableColumn): Boolean {
        return visibleTableColumns.contains(column.name)
    }

    override fun getState(): StockerSettingState {
        return myState
    }

    override fun loadState(state: StockerSettingState) {
        myState = state
        migrateLegacyListsToFavorites()
    }

    /**
     * One-shot migration: merges old per-market lists (aShareList, hkStocksList, etc.)
     * into the unified favoritesList. Also canonicalizes A-share codes.
     * Idempotent — skipped once favoritesMigrated is true.
     */
    private fun migrateLegacyListsToFavorites() {
        if (myState.favoritesMigrated) return
        if (myState.aShareList.isEmpty() && myState.hkStocksList.isEmpty() &&
            myState.usStocksList.isEmpty() && myState.cryptoList.isEmpty() &&
            myState.futuresList.isEmpty()
        ) {
            myState.favoritesMigrated = true
            return
        }

        val migrated = mutableListOf<String>()
        // Canonicalize A-share codes first
        myState.aShareList.map { StockerQuoteHttpUtil.canonicalAShareCode(it) }.distinct()
            .forEach { migrated.add(favoriteKey(StockerMarketType.AShare, it)) }
        myState.hkStocksList.distinct()
            .forEach { migrated.add(favoriteKey(StockerMarketType.HKStocks, it)) }
        myState.usStocksList.distinct()
            .forEach { migrated.add(favoriteKey(StockerMarketType.USStocks, it)) }
        myState.cryptoList.distinct()
            .forEach { migrated.add(favoriteKey(StockerMarketType.Crypto, it)) }
        myState.futuresList.distinct()
            .forEach { migrated.add(favoriteKey(StockerMarketType.Futures, it)) }

        // Merge with any existing favorites (avoid duplicates)
        val existing = myState.favoritesList.toSet()
        migrated.forEach { if (it !in existing) myState.favoritesList.add(it) }

        // Clear legacy lists and mark migration complete
        myState.aShareList.clear()
        myState.hkStocksList.clear()
        myState.usStocksList.clear()
        myState.cryptoList.clear()
        myState.futuresList.clear()
        myState.favoritesMigrated = true
        log.info("Migrated legacy per-market lists to unified favoritesList (${myState.favoritesList.size} entries)")
    }

}
