package com.vermouthx.stocker.settings

import com.vermouthx.stocker.enums.StockerMarketType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StockerSettingCascadeTest {

    private fun newSetting(): StockerSetting =
        StockerSetting().apply { loadState(StockerSettingState()) }

    @Test
    fun `removing the last favorite clears all per-code attachments`() {
        val setting = newSetting()
        val code = "SH600519"
        setting.addFavorite(StockerMarketType.AShare, code)
        setting.setCustomName(code, "茅台")
        setting.setCostPrice(code, 1500.0)
        setting.setHoldings(code, 100)
        setting.setPriceAlerts(code, above = 1600.0, below = 1400.0)
        setting.toggleFocusStock(code)

        setting.removeFavorite(StockerMarketType.AShare, code)

        assertNull(setting.getCustomName(code))
        assertNull(setting.getCostPrice(code))
        assertNull(setting.getHoldings(code))
        assertNull(setting.getAlertAbove(code))
        assertNull(setting.getAlertBelow(code))
        assertFalse(setting.isStockFocused(code))
    }

    @Test
    fun `reassigning favoritesList clears attachments of codes that vanished`() {
        // The management dialog commits deletions by rebuilding favoritesList wholesale,
        // bypassing removeFavorite — the cascade must cover this path too.
        val setting = newSetting()
        setting.addFavorite(StockerMarketType.AShare, "SH600519")
        setting.addFavorite(StockerMarketType.HKStocks, "00700")
        setting.setCostPrice("SH600519", 1500.0)
        setting.setCostPrice("00700", 410.0)

        setting.favoritesList =
            mutableListOf(setting.favoriteKey(StockerMarketType.HKStocks, "00700"))

        assertNull(setting.getCostPrice("SH600519"))
        assertEquals(410.0, setting.getCostPrice("00700"))
    }

    @Test
    fun `attachments survive while the code remains in another market`() {
        val setting = newSetting()
        val code = "00700"
        setting.addFavorite(StockerMarketType.HKStocks, code)
        setting.addFavorite(StockerMarketType.USStocks, code)
        setting.setCostPrice(code, 410.0)

        setting.removeFavorite(StockerMarketType.HKStocks, code)
        assertEquals(410.0, setting.getCostPrice(code))

        setting.removeFavorite(StockerMarketType.USStocks, code)
        assertNull(setting.getCostPrice(code))
    }
}
