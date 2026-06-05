package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic
import com.vermouthx.stocker.entities.StockerQuote

interface StockerQuoteUpdateNotifier {

    fun syncQuotes(quotes: List<StockerQuote>, size: Int)

    fun syncIndices(indices: List<StockerQuote>)

    companion object {
        @JvmField
        val STOCK_ALL_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("StockAllQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)

        @JvmField
        val STOCK_CN_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("StockCNQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)

        @JvmField
        val STOCK_HK_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("StockHKQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)

        @JvmField
        val STOCK_US_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("StockUSQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)

        @JvmField
        val CRYPTO_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("CryptoQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)

        @JvmField
        val FUTURES_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("FuturesQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)
    }
}
