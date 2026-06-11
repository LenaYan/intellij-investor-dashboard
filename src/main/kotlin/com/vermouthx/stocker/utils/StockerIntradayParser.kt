package com.vermouthx.stocker.utils

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.vermouthx.stocker.entities.StockerIntradayData

/**
 * Parses Tencent minute-data responses:
 * `min_data_CODE={"code":0,"msg":"","data":{"shCODE":{"data":{"data":["HHMM price volume amount",…]},"qt":{"shCODE":[…]}}}}`
 *
 * [code] is the bare uppercase symbol used as the table/cache key ("600519", "00700",
 * "AAPL"); the provider's own keys keep their exchange prefix, so qt lookup matches by
 * suffix. Returns null when the payload carries no usable minute prices.
 */
object StockerIntradayParser {

    fun parse(code: String, responseText: String): StockerIntradayData? {
        return try {
            val jsonStart = responseText.indexOf('{')
            if (jsonStart < 0) return null
            val root = JsonParser.parseString(responseText.substring(jsonStart)).asJsonObject

            val stock = root.getAsJsonObject("data")
                ?.entrySet()?.firstOrNull()?.value?.takeIf { it.isJsonObject }?.asJsonObject
                ?: return null

            val prices = stock.getAsJsonObject("data")
                ?.getAsJsonArray("data")
                ?.mapNotNull { entry ->
                    val parts = entry.takeIf { it.isJsonPrimitive }?.asString?.split(' ') ?: return@mapNotNull null
                    if (parts.size >= 2) parts[1].toDoubleOrNull() else null
                }
                ?: emptyList()
            if (prices.isEmpty()) return null

            StockerIntradayData(code, prices, previousClose(stock, code) ?: prices.first())
        } catch (_: Exception) {
            null
        }
    }

    /** Yesterday's close lives at index 4 of the stock's qt array. */
    private fun previousClose(stock: JsonObject, code: String): Double? {
        val qt = stock.getAsJsonObject("qt") ?: return null
        val qtArray = qt.entrySet()
            .firstOrNull { (key, value) ->
                !key.startsWith("v_ff") && key.endsWith(code, ignoreCase = true) && value.isJsonArray
            }
            ?.value?.asJsonArray
            ?: return null
        if (qtArray.size() <= 4) return null
        return qtArray[4].takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
    }
}
