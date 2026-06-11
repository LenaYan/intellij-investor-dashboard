package com.vermouthx.stocker.enums

import com.vermouthx.stocker.StockerBundle

enum class StockerStockOperation(private val key: String) {
    STOCK_ADD("operation.add"),
    STOCK_DELETE("operation.delete");

    val operation: String
        get() = StockerBundle.message(key)

    companion object {
        /**
         * The suggestion dialog round-trips the button label as toggle state, so this
         * must compare against the localized label — never a hardcoded literal.
         */
        @JvmStatic
        fun mapOf(des: String?): StockerStockOperation =
            if (STOCK_ADD.operation == des) STOCK_ADD else STOCK_DELETE
    }
}
