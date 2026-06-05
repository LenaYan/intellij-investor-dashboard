package com.vermouthx.stocker.utils

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.listeners.StockerQuoteDeleteNotifier
import com.vermouthx.stocker.settings.StockerSetting

object StockerActionUtil {

    @JvmStatic
    fun addStock(market: StockerMarketType, suggest: StockerSuggestion, project: Project?): Boolean {
        val setting = StockerSetting.instance
        // Canonicalize A-share codes to "SH000001"/"SZ000001"/"BJ430090". Search-dialog
        // suggestions already arrive prefixed; batch-add hands us raw user input like
        // "000001" that has to be routed to the right exchange before storage.
        val code = if (market == StockerMarketType.AShare) {
            StockerQuoteHttpUtil.canonicalAShareCode(suggest.code)
        } else {
            suggest.code
        }
        val fullName = suggest.name
        val provider = if (market == StockerMarketType.Crypto) setting.cryptoQuoteProvider else setting.quoteProvider
        if (setting.containsCode(code)) return false
        if (!StockerQuoteHttpUtil.validateCode(market, provider, code)) {
            Messages.showErrorDialog(project, "$fullName is not supported.", "Not Supported Stock")
            return false
        }
        return when (market) {
            StockerMarketType.AShare -> setting.aShareList.add(code)
            StockerMarketType.HKStocks -> setting.hkStocksList.add(code)
            StockerMarketType.USStocks -> setting.usStocksList.add(code)
            StockerMarketType.Crypto -> setting.cryptoList.add(code)
        }
    }

    @JvmStatic
    fun removeStock(market: StockerMarketType, suggest: StockerSuggestion): Boolean {
        val setting = StockerSetting.instance
        val messageBus = ApplicationManager.getApplication().messageBus
        setting.removeCode(market, suggest.code)
        val topic = when (market) {
            StockerMarketType.AShare -> StockerQuoteDeleteNotifier.STOCK_CN_QUOTE_DELETE_TOPIC
            StockerMarketType.HKStocks -> StockerQuoteDeleteNotifier.STOCK_HK_QUOTE_DELETE_TOPIC
            StockerMarketType.USStocks -> StockerQuoteDeleteNotifier.STOCK_US_QUOTE_DELETE_TOPIC
            StockerMarketType.Crypto -> StockerQuoteDeleteNotifier.CRYPTO_QUOTE_DELETE_TOPIC
        }
        messageBus.syncPublisher(StockerQuoteDeleteNotifier.STOCK_ALL_QUOTE_DELETE_TOPIC).after(suggest.code)
        messageBus.syncPublisher(topic).after(suggest.code)
        return true
    }
}
