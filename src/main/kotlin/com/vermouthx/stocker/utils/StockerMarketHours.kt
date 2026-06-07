package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Trading-hours table per market. Used to decide whether the consolidated
 * quote tick and the intraday tick should issue real HTTP this cycle.
 *
 * Windows that cross midnight (Futures night session) are encoded by storing
 * `start > end` and are matched accordingly.
 */
enum class StockerMarketSession(
    val market: StockerMarketType,
    private val zone: ZoneId,
    private val windows: List<Pair<LocalTime, LocalTime>>,
) {
    AShare(
        StockerMarketType.AShare,
        ZoneId.of("Asia/Shanghai"),
        listOf(LocalTime.of(9, 30) to LocalTime.of(11, 30),
               LocalTime.of(13, 0) to LocalTime.of(15, 0))
    ),
    HK(
        StockerMarketType.HKStocks,
        ZoneId.of("Asia/Shanghai"),
        listOf(LocalTime.of(9, 30) to LocalTime.of(12, 0),
               LocalTime.of(13, 0) to LocalTime.of(16, 0))
    ),
    US(
        StockerMarketType.USStocks,
        ZoneId.of("America/New_York"),
        listOf(LocalTime.of(9, 30) to LocalTime.of(16, 0))
    ),
    Futures(
        StockerMarketType.Futures,
        ZoneId.of("Asia/Shanghai"),
        listOf(LocalTime.of(9, 0)  to LocalTime.of(11, 30),
               LocalTime.of(13, 30) to LocalTime.of(15, 0),
               LocalTime.of(21, 0) to LocalTime.of(2, 30))
    ),
    Crypto(
        StockerMarketType.Crypto,
        ZoneId.of("UTC"),
        emptyList()
    );

    fun isOpen(now: Instant): Boolean {
        if (windows.isEmpty()) return true   // Crypto 24/7
        val zdt: ZonedDateTime = now.atZone(zone)
        val dow = zdt.dayOfWeek
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false
        val t = zdt.toLocalTime()
        return windows.any { (start, end) ->
            if (start <= end) t >= start && t < end
            else t >= start || t < end   // crosses midnight (Futures night)
        }
    }

    companion object {
        private val byMarket = entries.associateBy { it.market }
        fun of(market: StockerMarketType): StockerMarketSession? = byMarket[market]
    }
}
