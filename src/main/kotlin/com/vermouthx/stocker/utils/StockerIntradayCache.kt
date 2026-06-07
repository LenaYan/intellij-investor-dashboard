package com.vermouthx.stocker.utils

import com.vermouthx.stocker.entities.StockerIntradayData
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-symbol minute-keyed cache for intraday sparkline data. Same-minute reads
 * after a successful fetch are free; crossing a minute boundary invalidates.
 */
object StockerIntradayCache {

    private data class Entry(val data: StockerIntradayData, val minuteKey: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    fun get(code: String, now: Instant): StockerIntradayData? {
        val e = map[code] ?: return null
        return if (e.minuteKey == minuteKey(now)) e.data else null
    }

    fun put(code: String, data: StockerIntradayData, now: Instant) {
        map[code] = Entry(data, minuteKey(now))
    }

    fun evict(keep: Set<String>) {
        map.keys.retainAll(keep)
    }

    /** Visible for tests once a harness exists. */
    internal fun size(): Int = map.size

    private fun minuteKey(now: Instant): Long = now.epochSecond / 60
}
