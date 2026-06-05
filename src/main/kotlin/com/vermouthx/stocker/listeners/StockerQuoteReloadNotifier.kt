package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic

interface StockerQuoteReloadNotifier {

    fun clear()

    companion object {
        @JvmField
        val STOCK_ALL_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerAllQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)

        @JvmField
        val STOCK_CN_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerCNQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)

        @JvmField
        val STOCK_HK_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerHKQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)

        @JvmField
        val STOCK_US_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerUSQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)

        @JvmField
        val STOCK_CRYPTO_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerCryptoQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)

        @JvmField
        val STOCK_FUTURES_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerFuturesQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)
    }
}
