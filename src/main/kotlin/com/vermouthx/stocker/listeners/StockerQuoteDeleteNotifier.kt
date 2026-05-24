package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic

interface StockerQuoteDeleteNotifier {

    fun after(code: String)

    companion object {
        @JvmField
        val STOCK_ALL_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("StockAllQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)

        @JvmField
        val STOCK_CN_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("StockCNQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)

        @JvmField
        val STOCK_HK_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("StockHKQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)

        @JvmField
        val STOCK_US_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("StockUSQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)

        @JvmField
        val CRYPTO_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("CryptoQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)
    }
}
