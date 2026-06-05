package com.vermouthx.stocker.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.listeners.StockerQuoteDeleteNotifier
import com.vermouthx.stocker.services.StockerCloudSyncService
import com.vermouthx.stocker.settings.StockerSetting

object StockerActionUtil {

    @JvmStatic
    fun addStock(market: StockerMarketType, suggest: StockerSuggestion, project: Project?): Boolean {
        val setting = StockerSetting.instance
        val code = if (market == StockerMarketType.AShare) {
            StockerQuoteHttpUtil.canonicalAShareCode(suggest.code)
        } else if (market == StockerMarketType.Futures) {
            suggest.code.uppercase()
        } else {
            suggest.code
        }
        val fullName = suggest.name
        val provider = when (market) {
            StockerMarketType.Crypto -> setting.cryptoQuoteProvider
            StockerMarketType.Futures -> StockerQuoteProvider.SINA
            else -> setting.quoteProvider
        }
        if (setting.containsFavorite(market, code)) return false
        if (!StockerQuoteHttpUtil.validateCode(market, provider, code)) {
            Messages.showErrorDialog(project, "$fullName is not supported.", "Not Supported Stock")
            return false
        }
        val added = setting.addFavorite(market, code)
        if (added) {
            pushToCloudIfEnabled(market, code, isAdd = true)
        }
        return added
    }

    @JvmStatic
    fun removeStock(market: StockerMarketType, suggest: StockerSuggestion): Boolean {
        val setting = StockerSetting.instance
        val messageBus = ApplicationManager.getApplication().messageBus
        setting.removeFavorite(market, suggest.code)
        messageBus.syncPublisher(StockerQuoteDeleteNotifier.STOCK_ALL_QUOTE_DELETE_TOPIC).after(suggest.code)
        pushToCloudIfEnabled(market, suggest.code, isAdd = false)
        return true
    }

    private fun pushToCloudIfEnabled(market: StockerMarketType, code: String, isAdd: Boolean) {
        val setting = StockerSetting.instance
        if (!setting.cloudSyncEnabled || !setting.cloudSyncAutoEnabled || setting.cloudSyncApiKey.isBlank()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val syncService = StockerCloudSyncService.instance
            syncService.baseUrl = setting.cloudSyncBaseUrl
            syncService.apiKey = setting.cloudSyncApiKey
            if (isAdd) {
                syncService.addFavoriteToCloud(market, code)
            } else {
                syncService.removeFavoriteFromCloud(market, code)
            }
        }
    }
}
