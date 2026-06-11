package com.vermouthx.stocker.listeners

import com.intellij.util.messages.Topic
import java.time.Instant

enum class StockerRefreshState {
    /** A relevant market is open and quotes refresh at the configured interval. */
    LIVE,

    /** All relevant markets closed — consolidated tick throttled to ~1/min. */
    OFF_HOURS,

    /** Tool window hidden — refresh suspended. */
    PAUSED,

    /** Consecutive fetch failures — exponential backoff active. */
    BACKOFF,
}

data class StockerRefreshStatus(
    val state: StockerRefreshState,
    val intervalSeconds: Long,
    val lastSuccessAt: Instant?,
)

/**
 * Published by [com.vermouthx.stocker.StockerApp] on every consolidated tick so the
 * tool window can show *why* the table is (or isn't) moving. Without this surface a
 * throttled or paused dashboard is indistinguishable from a broken one.
 */
interface StockerRefreshStatusNotifier {

    fun statusChanged(status: StockerRefreshStatus)

    companion object {
        val REFRESH_STATUS_TOPIC: Topic<StockerRefreshStatusNotifier> =
            Topic.create("StockerRefreshStatus", StockerRefreshStatusNotifier::class.java)
    }
}
