package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StockerCodeMarketGrouperTest {

    private val favorites = mapOf(
        "SH600519" to StockerMarketType.AShare,
        "00700" to StockerMarketType.HKStocks,
    )
    private val favoriteResolver: (String) -> StockerMarketType? = { favorites[it] }

    @Test
    fun `groups favorite codes by their favorite market`() {
        val grouped = StockerCodeMarketGrouper.group(
            setOf("SH600519", "00700"), favoriteResolver, emptyMap(),
        )
        assertEquals(setOf("SH600519"), grouped[StockerMarketType.AShare])
        assertEquals(setOf("00700"), grouped[StockerMarketType.HKStocks])
    }

    @Test
    fun `falls back to the watchlist mapping for codes not in favorites`() {
        val watchlist = mapOf(
            StockerMarketType.USStocks to listOf("NVDA"),
            StockerMarketType.AShare to listOf("SZ000001"),
        )
        val grouped = StockerCodeMarketGrouper.group(
            setOf("NVDA", "SZ000001"), favoriteResolver, watchlist,
        )
        assertEquals(setOf("NVDA"), grouped[StockerMarketType.USStocks])
        assertEquals(setOf("SZ000001"), grouped[StockerMarketType.AShare])
    }

    @Test
    fun `favorites take precedence over a conflicting watchlist mapping`() {
        val watchlist = mapOf(StockerMarketType.USStocks to listOf("00700"))
        val grouped = StockerCodeMarketGrouper.group(
            setOf("00700"), favoriteResolver, watchlist,
        )
        assertEquals(setOf("00700"), grouped[StockerMarketType.HKStocks])
        assertTrue(grouped[StockerMarketType.USStocks].isNullOrEmpty())
    }

    @Test
    fun `drops codes resolvable by neither source`() {
        val grouped = StockerCodeMarketGrouper.group(
            setOf("UNKNOWN"), favoriteResolver, emptyMap(),
        )
        assertTrue(grouped.isEmpty())
    }
}
