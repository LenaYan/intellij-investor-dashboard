package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic

interface StockerQuoteReloadNotifier {

    fun clear()

    companion object {
        @JvmField
        val STOCK_ALL_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerAllQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)
    }
}
