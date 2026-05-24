package com.vermouthx.stocker.enums

enum class StockerStockOperation(val operation: String) {
    STOCK_ADD("Add"),
    STOCK_DELETE("Delete");

    companion object {
        @JvmStatic
        fun mapOf(des: String?): StockerStockOperation =
            if ("Add" == des) STOCK_ADD else STOCK_DELETE
    }
}
