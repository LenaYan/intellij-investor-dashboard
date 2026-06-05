package com.vermouthx.stocker.enums

enum class StockerMarketType(val title: String, val persistedId: String) {
    AShare("CN", "CN"),
    HKStocks("HK", "HK"),
    USStocks("US", "US"),
    Crypto("Crypto", "CRYPTO"),
    Futures("Futures", "FUTURES");

    companion object {
        private val byPersistedId = entries.associateBy { it.persistedId }

        fun fromPersistedId(id: String): StockerMarketType? = byPersistedId[id.uppercase()]
    }
}