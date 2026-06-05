package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic

interface StockerQuoteDeleteNotifier {

    fun after(code: String)

    companion object {
        @JvmField
        val STOCK_ALL_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("StockAllQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)
    }
}
