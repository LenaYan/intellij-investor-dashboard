package com.vermouthx.stocker.utils

import com.vermouthx.stocker.entities.StockerIntradayData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class StockerIntradayCacheTest {

    private val data = StockerIntradayData("SH600519", listOf(1270.0, 1271.0), 1275.88)

    @BeforeEach
    fun clearCache() {
        StockerIntradayCache.evict(emptySet())
    }

    @Test
    fun `same minute read hits`() {
        val t0 = Instant.parse("2026-06-11T03:00:10Z")
        val t1 = Instant.parse("2026-06-11T03:00:50Z")
        StockerIntradayCache.put("SH600519", data, t0)
        assertEquals(data, StockerIntradayCache.get("SH600519", t1))
    }

    @Test
    fun `crossing the minute boundary invalidates`() {
        val t0 = Instant.parse("2026-06-11T03:00:59Z")
        val t1 = Instant.parse("2026-06-11T03:01:00Z")
        StockerIntradayCache.put("SH600519", data, t0)
        assertNull(StockerIntradayCache.get("SH600519", t1))
    }

    @Test
    fun `evict retains only the keep set`() {
        val now = Instant.parse("2026-06-11T03:00:00Z")
        StockerIntradayCache.put("SH600519", data, now)
        StockerIntradayCache.put("SZ000001", data.copy(code = "SZ000001"), now)
        StockerIntradayCache.evict(setOf("SH600519"))
        assertEquals(1, StockerIntradayCache.size())
        assertEquals("SH600519", StockerIntradayCache.get("SH600519", now)?.code)
    }
}
