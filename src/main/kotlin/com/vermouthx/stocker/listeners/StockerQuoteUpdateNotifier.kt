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
    }
}
