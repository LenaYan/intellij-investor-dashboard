package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class StockerMarketSessionTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val newYork = ZoneId.of("America/New_York")

    private fun at(zone: ZoneId, dateTime: String): Instant =
        LocalDateTime.parse(dateTime).atZone(zone).toInstant()

    // 2026-06-10 is a Wednesday, 2026-06-13 a Saturday.

    @Test
    fun `a-share open mid-morning on a weekday`() {
        val session = StockerMarketSession.of(StockerMarketType.AShare)!!
        assertTrue(session.isOpen(at(shanghai, "2026-06-10T10:00:00")))
    }

    @Test
    fun `a-share closed during lunch break and after close`() {
        val session = StockerMarketSession.of(StockerMarketType.AShare)!!
        assertFalse(session.isOpen(at(shanghai, "2026-06-10T12:00:00")))
        assertFalse(session.isOpen(at(shanghai, "2026-06-10T15:30:00")))
    }

    @Test
    fun `a-share closed on saturday`() {
        val session = StockerMarketSession.of(StockerMarketType.AShare)!!
        assertFalse(session.isOpen(at(shanghai, "2026-06-13T10:00:00")))
    }

    @Test
    fun `futures night session spans midnight`() {
        val session = StockerMarketSession.of(StockerMarketType.Futures)!!
        assertTrue(session.isOpen(at(shanghai, "2026-06-10T22:00:00")), "before midnight")
        assertTrue(session.isOpen(at(shanghai, "2026-06-11T01:00:00")), "after midnight")
        assertFalse(session.isOpen(at(shanghai, "2026-06-11T03:00:00")), "past 02:30 close")
    }

    @Test
    fun `us session uses new york local time`() {
        val session = StockerMarketSession.of(StockerMarketType.USStocks)!!
        assertTrue(session.isOpen(at(newYork, "2026-06-10T10:00:00")))
        assertFalse(session.isOpen(at(newYork, "2026-06-10T17:00:00")))
    }

    @Test
    fun `crypto is always open`() {
        val session = StockerMarketSession.of(StockerMarketType.Crypto)!!
        assertTrue(session.isOpen(at(shanghai, "2026-06-13T03:00:00")), "even on weekends at night")
    }
}
