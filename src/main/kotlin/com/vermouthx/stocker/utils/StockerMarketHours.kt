package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Mainland exchange closures (weekday dates only — weekends are excluded by the
 * day-of-week check). Sourced from the State Council 2026 holiday schedule; must be
 * refreshed once a year. Bias rule: when unsure, leave a date OUT — a missing holiday
 * merely fetches static quotes, while a wrongly listed trading day throttles live data.
 */
internal object StockerCnHolidays {
    val DATES: Set<LocalDate> = setOf(
        // New Year's Day
        LocalDate.of(2026, 1, 1),
        LocalDate.of(2026, 1, 2),
        // Spring Festival week (除夕 Mon 2/16 .. 初五 Fri 2/20)
        LocalDate.of(2026, 2, 16),
        LocalDate.of(2026, 2, 17),
        LocalDate.of(2026, 2, 18),
        LocalDate.of(2026, 2, 19),
        LocalDate.of(2026, 2, 20),
        // Qingming (4/5 falls on Sunday; Monday observed)
        LocalDate.of(2026, 4, 6),
        // Labour Day
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 4),
        LocalDate.of(2026, 5, 5),
        // Dragon Boat Festival
        LocalDate.of(2026, 6, 19),
        // Mid-Autumn Festival
        LocalDate.of(2026, 9, 25),
        // National Day week
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 2),
        LocalDate.of(2026, 10, 5),
        LocalDate.of(2026, 10, 6),
        LocalDate.of(2026, 10, 7),
    )
}

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
        // 9:15 covers the call-auction phase — prices already move then and the
        // 15 minutes before the bell are peak dashboard-watching time.
        listOf(LocalTime.of(9, 15) to LocalTime.of(11, 30),
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
        // Mainland exchanges (A-share, domestic futures) also close on statutory
        // holidays; HK and US calendars differ and are not modelled here.
        if ((market == StockerMarketType.AShare || market == StockerMarketType.Futures) &&
            zdt.toLocalDate() in StockerCnHolidays.DATES
        ) {
            return false
        }
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
