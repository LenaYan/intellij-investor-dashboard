# Refresh Efficiency Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cut steady-state HTTP traffic and EDT load for the Favorites / Watchlist data lists by gating refresh on window/IDE visibility, market hours, viewport, and failure backoff — without changing user-visible features.

**Architecture:** Dual `ScheduledExecutorService` inside `StockerApp` (quote @ `refreshInterval`, intraday @ 60s). Both share a soft-pause `state` field plus market-hours and failure-backoff gates. Intraday additionally consults a minute-keyed cache and a viewport-snapshot maintained by `StockerTableView`.

**Tech Stack:** Kotlin 1.9, IntelliJ Platform 2024.x, Apache HttpClient 4.x, Swing `DefaultTableModel`, `java.time` (`ZoneId` + `ZonedDateTime`).

**Spec:** [`2026-06-07-refresh-efficiency-design.md`](../specs/2026-06-07-refresh-efficiency-design.md)

**No test harness:** This project has no `src/test` directory. Every task ends with `./gradlew buildPlugin` + a manual smoke-test note. Subagents must NOT scaffold a test harness as part of this work.

---

## Task 1 — HTTP pool: cap per-route concurrency (§4)

Single-line change to reduce burst load against `web.ifzq.gtimg.cn`.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/utils/StockerHttpClientPool.kt:25`

- [ ] **Step 1: Lower `defaultMaxPerRoute`**

Replace line 25:

```kotlin
            maxTotal = 20
            defaultMaxPerRoute = 3
```

- [ ] **Step 2: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerHttpClientPool.kt
git commit -m "perf(http): cap defaultMaxPerRoute at 3 to reduce ifzq throttle"
```

---

## Task 2 — Soft pause/resume on `StockerApp` (§1, part 1 of 3)

Adds the state plumbing. The listener wiring lands in Task 3.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

- [ ] **Step 1: Add `state` + helpers**

At the top of class body (after the `refreshActive` line at `StockerApp.kt:28`), add:

```kotlin
    enum class State { RUNNING, PAUSED }

    @Volatile
    private var state: State = State.RUNNING

    fun pause() { state = State.PAUSED }
    fun resume() { state = State.RUNNING }
    fun isPaused(): Boolean = state == State.PAUSED
```

- [ ] **Step 2: Gate the consolidated task on `state`**

Inside `createConsolidatedUpdateThread()` (`StockerApp.kt:69-127`), at the very top of the lambda body — BEFORE the existing `if (!shouldContinueRefresh()) return@Runnable` — insert:

```kotlin
            if (state == State.PAUSED) {
                return@Runnable
            }
```

- [ ] **Step 3: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "perf(refresh): add soft pause/resume on StockerApp"
```

---

## Task 3 — Wire visibility + IDE-focus listeners (§1, part 2 of 3)

Connect tool-window visibility and IDE activation to `pause()` / `resume()`.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/views/windows/StockerToolWindow.kt`

- [ ] **Step 1: Add imports**

Near the existing imports add:

```kotlin
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
```

- [ ] **Step 2: Track viz state**

After the existing `private var watchlistRefreshListener: (() -> Unit)? = null` field, add:

```kotlin
    @Volatile private var toolWindowVisible: Boolean = true
    @Volatile private var ideActive: Boolean = true
    private var toolWindowId: String? = null
```

- [ ] **Step 3: Add the pause-driver helper**

Below the `cleanup()` method, add:

```kotlin
    private fun applyPauseState() {
        if (toolWindowVisible && ideActive) {
            myApplication.resume()
        } else {
            myApplication.pause()
        }
    }
```

- [ ] **Step 4: Register both listeners in `createToolWindowContent`**

Inside `createToolWindowContent`, after `StockerAppManager.register(project, myApplication)` and before `myApplication.schedule()` (currently at `StockerToolWindow.kt:101-102`), insert:

```kotlin
        toolWindowId = toolWindow.id

        // IDE activation listener — application-level bus.
        messageBusConnections.add(
            ApplicationManager.getApplication().messageBus.connect().apply {
                subscribe(ApplicationActivationListener.TOPIC, object : ApplicationActivationListener {
                    override fun applicationActivated(ideFrame: IdeFrame) {
                        ideActive = true
                        applyPauseState()
                    }
                    override fun applicationDeactivated(ideFrame: IdeFrame) {
                        ideActive = false
                        applyPauseState()
                    }
                })
            }
        )

        // Tool-window visibility — project-level bus.
        messageBusConnections.add(
            project.messageBus.connect().apply {
                subscribe(ToolWindowManagerListener.TOPIC, object : ToolWindowManagerListener {
                    override fun stateChanged(manager: com.intellij.openapi.wm.ToolWindowManager) {
                        val id = toolWindowId ?: return
                        val tw = manager.getToolWindow(id) ?: return
                        toolWindowVisible = tw.isVisible
                        applyPauseState()
                    }
                })
            }
        )
```

The `messageBusConnections.forEach { it.disconnect() }` call already in `cleanup()` will dispose both new subscriptions.

- [ ] **Step 5: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Smoke test**

Run: `./gradlew runIde` (in a separate terminal).
In the launched IDE: open Stocker tool window, observe normal refresh; collapse the tool window; observe in idea.log that no quote HTTP fires for at least 10 s; reopen and confirm refresh resumes within `refreshInterval`. Minimize the IDE window; confirm refresh pauses.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/views/windows/StockerToolWindow.kt
git commit -m "perf(refresh): pause refresh when tool window hidden or IDE inactive"
```

---

## Task 4 — Market-hours table (§2, part 1 of 2)

Create the data type. Wiring in Task 5.

**Files:**
- Create: `src/main/kotlin/com/vermouthx/stocker/utils/StockerMarketHours.kt`

- [ ] **Step 1: Write the file**

```kotlin
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
```

- [ ] **Step 2: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerMarketHours.kt
git commit -m "feat(refresh): introduce StockerMarketSession trading-hours table"
```

---

## Task 5 — Off-hours throttling in `StockerApp` (§2, part 2 of 2)

Skip most quote ticks (and all intraday ticks) when nothing the user follows is open.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

- [ ] **Step 1: Add a tick counter and helper**

After the `state` field added in Task 2, add:

```kotlin
    @Volatile private var offHoursTickCounter: Long = 0
```

Below `createConsolidatedUpdateThread()` (i.e. before `fetchQuotesIfActive`), add a private helper:

```kotlin
    /**
     * Returns true when at least one market the user has codes in is currently open.
     * Codes are taken from the unified favourites list plus the finance/ watchlist
     * additions — same union the consolidated task already builds.
     */
    private fun anyRelevantMarketOpen(now: java.time.Instant): Boolean {
        val watchlistByMarket = com.vermouthx.stocker.finance.FinanceBridgeService.instance.watchlistCodesByMarket()
        for (market in com.vermouthx.stocker.enums.StockerMarketType.entries) {
            val codes = setting.codesByMarket(market) +
                        (watchlistByMarket[market] ?: emptyList())
            if (codes.isEmpty()) continue
            val session = com.vermouthx.stocker.utils.StockerMarketSession.of(market) ?: continue
            if (session.isOpen(now)) return true
        }
        return false
    }
```

- [ ] **Step 2: Gate the consolidated task on off-hours**

Inside `createConsolidatedUpdateThread()`, immediately after the `if (state == State.PAUSED) return@Runnable` line added in Task 2, insert:

```kotlin
            val now = java.time.Instant.now()
            if (!anyRelevantMarketOpen(now)) {
                offHoursTickCounter++
                val ticksPerMinute = (60L / setting.refreshInterval.coerceAtLeast(1L)).coerceAtLeast(1L)
                if (offHoursTickCounter % ticksPerMinute != 0L) {
                    return@Runnable
                }
            } else {
                offHoursTickCounter = 0
            }
```

- [ ] **Step 3: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Smoke test (only if currently outside all listed market hours)**

Run: `./gradlew runIde`.
In idea.log, expect one quote-tick fetch roughly every 60 s instead of every 5 s. If currently inside any market hour, skip the smoke check.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "perf(refresh): throttle quote tick to ~1/min when all markets closed"
```

---

## Task 6 — Quote fetch returns `Result` (§3, part 1 of 2)

Convert `StockerQuoteHttpUtil.get(...)` to a `Result` so the scheduler can distinguish failure from "no codes".

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt:72-103`
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt` (call sites)

- [ ] **Step 1: Change the return type**

Replace the body of `fun get(...)` in `StockerQuoteHttpUtil.kt:72-103` with:

```kotlin
    fun get(
        marketType: StockerMarketType, quoteProvider: StockerQuoteProvider, codes: List<String>
    ): Result<List<StockerQuote>> {
        if (codes.isEmpty()) {
            return Result.success(emptyList())
        }

        if (quoteProvider.providerPrefixMap[marketType] == null) {
            log.warn("Provider ${quoteProvider.title} does not support market type $marketType")
            return Result.success(emptyList())
        }

        val codesParam = codes.joinToString(",") { code ->
            providerSymbol(quoteProvider, marketType, code) ?: ""
        }

        val url = "${quoteProvider.host}${codesParam}"
        val httpGet = HttpGet(url)
        if (quoteProvider == StockerQuoteProvider.SINA) {
            httpGet.setHeader("Referer", "https://finance.sina.com.cn")
        }
        return try {
            httpClientPool.client().execute(httpGet).use { response ->
                val responseText = EntityUtils.toString(response.entity, "UTF-8")
                Result.success(StockerQuoteParser.parseQuoteResponse(quoteProvider, marketType, responseText))
            }
        } catch (e: Exception) {
            log.warn(e)
            Result.failure(e)
        }
    }
```

Notes: an empty-codes call returns `success(emptyList())` so the caller does not count "user has nothing in this market" as a failure. A provider-mismatch warning is also `success(emptyList())` for the same reason.

- [ ] **Step 2: Adapt `StockerApp.fetchQuotesIfActive`**

In `StockerApp.kt:129-138`, replace the helper with:

```kotlin
    private fun fetchQuotesIfActive(
        marketType: StockerMarketType,
        quoteProvider: StockerQuoteProvider,
        codes: List<String>
    ): Result<List<StockerQuote>>? {
        if (!shouldContinueRefresh()) {
            return null
        }
        return StockerQuoteHttpUtil.get(marketType, quoteProvider, codes)
    }
```

- [ ] **Step 3: Update call sites in `createConsolidatedUpdateThread`**

In `StockerApp.kt:87-96`, each line of the form

```kotlin
val aShareQuotes = fetchQuotesIfActive(StockerMarketType.AShare, quoteProvider, aShareCodes) ?: return@Runnable
```

becomes a `Result` that we both *unwrap* and *track*. Replace lines 87-96 with:

```kotlin
            val aShareResult     = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        aShareCodes)  ?: return@Runnable
            val hkStocksResult   = fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        hkCodes)      ?: return@Runnable
            val usStocksResult   = fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        usCodes)      ?: return@Runnable
            val cryptoResult     = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  cryptoCodes)  ?: return@Runnable
            val futuresResult    = fetchQuotesIfActive(StockerMarketType.Futures,  StockerQuoteProvider.SINA, futuresCodes) ?: return@Runnable

            val aShareIdxResult  = fetchQuotesIfActive(StockerMarketType.AShare,   quoteProvider,        StockerMarketIndex.CN.codes)     ?: return@Runnable
            val hkStocksIdxResult= fetchQuotesIfActive(StockerMarketType.HKStocks, quoteProvider,        StockerMarketIndex.HK.codes)     ?: return@Runnable
            val usStocksIdxResult= fetchQuotesIfActive(StockerMarketType.USStocks, quoteProvider,        StockerMarketIndex.US.codes)     ?: return@Runnable
            val cryptoIdxResult  = fetchQuotesIfActive(StockerMarketType.Crypto,   cryptoQuoteProvider,  StockerMarketIndex.Crypto.codes) ?: return@Runnable

            val allResults = listOf(
                aShareResult, hkStocksResult, usStocksResult, cryptoResult, futuresResult,
                aShareIdxResult, hkStocksIdxResult, usStocksIdxResult, cryptoIdxResult,
            )
            val anyFailure = allResults.any { it.isFailure }

            val aShareQuotes    = aShareResult.getOrDefault(emptyList())
            val hkStocksQuotes  = hkStocksResult.getOrDefault(emptyList())
            val usStocksQuotes  = usStocksResult.getOrDefault(emptyList())
            val cryptoQuotes    = cryptoResult.getOrDefault(emptyList())
            val futuresQuotes   = futuresResult.getOrDefault(emptyList())
            val aShareIndices   = aShareIdxResult.getOrDefault(emptyList())
            val hkStocksIndices = hkStocksIdxResult.getOrDefault(emptyList())
            val usStocksIndices = usStocksIdxResult.getOrDefault(emptyList())
            val cryptoIndices   = cryptoIdxResult.getOrDefault(emptyList())
```

`anyFailure` is consumed by Task 7.

- [ ] **Step 4: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt \
        src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "refactor(http): quote fetch returns Result for backoff classification"
```

---

## Task 7 — Exponential backoff (§3, part 2 of 2)

Use the failure signal from Task 6 to skip future ticks under throttling.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

- [ ] **Step 1: Add backoff state**

After the `offHoursTickCounter` field, add:

```kotlin
    @Volatile private var consecutiveFailures: Int = 0
    @Volatile private var skipUntil: java.time.Instant = java.time.Instant.MIN
```

- [ ] **Step 2: Gate the consolidated task on `skipUntil`**

Inside `createConsolidatedUpdateThread()`, right after the off-hours block added in Task 5 and before `val quoteProvider = setting.quoteProvider`, insert:

```kotlin
            if (now.isBefore(skipUntil)) {
                return@Runnable
            }
```

(`now` was already declared by Task 5; reuse it.)

- [ ] **Step 3: Update backoff state at end of tick**

In the same lambda, immediately after the publish block (right after `allPublisher.syncIndices(allStockIndices)`) and before the intraday fetch block, insert:

```kotlin
            if (anyFailure) {
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    val baseSec = setting.refreshInterval.coerceAtLeast(1L)
                    val shift = (consecutiveFailures - 2).coerceAtMost(8)
                    val delaySec = minOf(300L, baseSec * (1L shl shift))
                    skipUntil = now.plusSeconds(delaySec)
                    val log = com.intellij.openapi.diagnostic.Logger.getInstance(StockerApp::class.java)
                    log.warn("quote backoff: failures=$consecutiveFailures, next=${delaySec}s")
                }
            } else if (consecutiveFailures > 0) {
                val log = com.intellij.openapi.diagnostic.Logger.getInstance(StockerApp::class.java)
                log.info("quote recovered after $consecutiveFailures failures")
                consecutiveFailures = 0
                skipUntil = java.time.Instant.MIN
            }
```

- [ ] **Step 4: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Smoke test**

Temporarily edit `StockerSetting.quoteProvider` via Settings UI to break connectivity (or pull network), launch `./gradlew runIde`, confirm in idea.log:
1. Three failure ticks pass with normal cadence,
2. Then a `quote backoff` WARN with `next=10s`,
3. Subsequent failures produce 20s, 40s, …, capped at 300s,
4. Restoring connectivity yields `quote recovered after N failures` on next successful tick.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "perf(refresh): exponential backoff after 3 consecutive quote failures"
```

---

## Task 8 — Intraday cache (§6, part 1 of 4)

Add the minute-keyed cache. Standalone, no callers yet.

**Files:**
- Create: `src/main/kotlin/com/vermouthx/stocker/utils/StockerIntradayCache.kt`

- [ ] **Step 1: Write the file**

```kotlin
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
```

- [ ] **Step 2: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerIntradayCache.kt
git commit -m "feat(intraday): add minute-keyed intraday cache"
```

---

## Task 9 — Viewport snapshot on `StockerTableView` (§6, part 2 of 4)

Track which rows are visible so the intraday tick can fetch only those.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/views/StockerTableView.kt`

- [ ] **Step 1: Add snapshot data class + field**

Near the top of `class StockerTableView` body (around the existing `@Volatile private var disposed: Boolean = false` at line 96-97), add:

```kotlin
    data class VisibleSnapshot(val codes: List<String>)

    @Volatile
    private var visibleSnapshot: VisibleSnapshot = VisibleSnapshot(emptyList())
```

- [ ] **Step 2: Add the snapshot updater**

Anywhere inside the class (next to other private helpers), add:

```kotlin
    /** Recompute the visible-rows snapshot. Must be called on the EDT. */
    private fun refreshVisibleSnapshot() {
        val rowCount = tbModel.rowCount
        if (rowCount == 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val rect = tbBody.visibleRect
        if (rect.width <= 0 || rect.height <= 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val firstRaw = tbBody.rowAtPoint(java.awt.Point(0, rect.y))
        val lastRaw = tbBody.rowAtPoint(java.awt.Point(0, rect.y + rect.height - 1))
        val first = (if (firstRaw < 0) 0 else firstRaw) - 5
        val last  = (if (lastRaw  < 0) rowCount - 1 else lastRaw) + 5
        val clampedFirst = first.coerceAtLeast(0)
        val clampedLast  = last.coerceAtMost(rowCount - 1)
        val symbolCol = com.vermouthx.stocker.utils.StockerTableModelUtil
            .colOf(tbModel, com.vermouthx.stocker.enums.StockerTableColumn.SYMBOL)
        if (symbolCol < 0) {
            visibleSnapshot = VisibleSnapshot(emptyList())
            return
        }
        val codes = ArrayList<String>(clampedLast - clampedFirst + 1)
        for (modelRow in clampedFirst..clampedLast) {
            val v = tbModel.getValueAt(modelRow, symbolCol) ?: continue
            codes.add(v.toString())
        }
        visibleSnapshot = VisibleSnapshot(codes)
    }
```

Note: `tbBody` is the `JBTable` instance (declared at `StockerTableView.kt:66`), `tbModel` is the `DefaultTableModel` subclass (declared at `:67`), `tbPane` is the `JScrollPane` wrapping `tbBody` (declared at `:62`). All three are `private lateinit` so this code must live inside the class body.

- [ ] **Step 3: Hook refresh triggers**

Inside `initTable()` at the END of the method (after all table setup; this method ends before `initPane()`), add:

```kotlin
        // Keep visible-snapshot fresh.
        tbBody.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = refreshVisibleSnapshot()
            override fun componentShown(e: java.awt.event.ComponentEvent)   = refreshVisibleSnapshot()
        })
        tbModel.addTableModelListener { refreshVisibleSnapshot() }
```

Inside `initPane()`, AFTER `tbPane` is assigned (search for `tbPane =` to locate the line), add:

```kotlin
        tbPane.verticalScrollBar.addAdjustmentListener { refreshVisibleSnapshot() }
        javax.swing.SwingUtilities.invokeLater { refreshVisibleSnapshot() }
```

- [ ] **Step 4: Expose visible-codes statically**

At the bottom of the companion object (next to `syncAllIntradayData` at `StockerTableView.kt:1044`), add:

```kotlin
        /** Snapshot of codes currently visible across all active table views, grouped by market. */
        @JvmStatic
        fun visibleCodesByMarket(): Map<com.vermouthx.stocker.enums.StockerMarketType, Set<String>> {
            val setting = com.vermouthx.stocker.settings.StockerSetting.instance
            val grouped = HashMap<com.vermouthx.stocker.enums.StockerMarketType, MutableSet<String>>()
            synchronized(tableViews) {
                for (view in tableViews) {
                    for (code in view.visibleSnapshot.codes) {
                        val m = setting.marketOf(code) ?: continue
                        grouped.getOrPut(m) { HashSet() }.add(code)
                    }
                }
            }
            return grouped
        }
```

- [ ] **Step 5: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/views/StockerTableView.kt
git commit -m "feat(intraday): track visible row snapshot per table view"
```

---

## Task 10 — Split intraday into its own scheduler (§6, part 3 of 4)

Remove intraday from the quote consolidated task; give it its own 60 s executor that consults Task 8 cache and Task 9 viewport.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

- [ ] **Step 1: Add the second executor and its period**

Below the existing `private var scheduledExecutorService` at `StockerApp.kt:23`, add:

```kotlin
    private var intradayExecutor: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newScheduledThreadPool(1)

    private val intradayPeriodSeconds: Long = 60L

    @Volatile private var intradayTickCounter: Long = 0
```

- [ ] **Step 2: Schedule it in `schedule()`**

Inside `schedule()` (`StockerApp.kt:30-44`), at the end of the existing method, add:

```kotlin
        if (intradayExecutor.isShutdown) {
            intradayExecutor = java.util.concurrent.Executors.newScheduledThreadPool(1)
        }
        intradayExecutor.scheduleAtFixedRate(
            createIntradayUpdateThread(),
            intradayPeriodSeconds,
            intradayPeriodSeconds,
            java.util.concurrent.TimeUnit.SECONDS,
        )
```

- [ ] **Step 3: Shut it down in `shutdown()`**

Inside `shutdown()` (`StockerApp.kt:46-50`), before `StockerQuoteHttpUtil.closeConnections()`, add:

```kotlin
        intradayExecutor.shutdownNow()
```

- [ ] **Step 4: Remove the in-line intraday block from the consolidated task**

In `createConsolidatedUpdateThread()` delete lines 109-125 (the entire block starting with `// Fetch intraday data for sparkline display` and ending with the closing brace before `}` of the `Runnable`):

```kotlin
            // Fetch intraday data for sparkline display
            if (!shouldContinueRefresh()) return@Runnable
            val intradayMap = mutableMapOf<String, com.vermouthx.stocker.entities.StockerIntradayData>()
            if (aShareCodes.isNotEmpty()) { ... }
            ...
            if (intradayMap.isNotEmpty()) {
                StockerTableView.syncAllIntradayData(intradayMap)
            }
```

Replace with: nothing. The quote task ends after the backoff bookkeeping from Task 7.

- [ ] **Step 5: Add the new intraday task**

Add as a private method on `StockerApp`, next to `createConsolidatedUpdateThread`:

```kotlin
    /**
     * Independent 60s intraday update: viewport + cache aware. Skipped entirely
     * when paused or when no relevant market is open.
     */
    private fun createIntradayUpdateThread(): Runnable {
        return Runnable {
            if (state == State.PAUSED) return@Runnable
            if (!shouldContinueRefresh()) return@Runnable

            val now = java.time.Instant.now()
            if (!anyRelevantMarketOpen(now)) return@Runnable

            val visible = com.vermouthx.stocker.views.StockerTableView.visibleCodesByMarket()
            if (visible.isEmpty()) return@Runnable

            val toBroadcast = HashMap<String, com.vermouthx.stocker.entities.StockerIntradayData>()
            val intradayMarkets = setOf(
                com.vermouthx.stocker.enums.StockerMarketType.AShare,
                com.vermouthx.stocker.enums.StockerMarketType.HKStocks,
                com.vermouthx.stocker.enums.StockerMarketType.USStocks,
            )

            for ((market, codes) in visible) {
                if (market !in intradayMarkets) continue
                if (!shouldContinueRefresh()) return@Runnable

                val hits  = HashMap<String, com.vermouthx.stocker.entities.StockerIntradayData>()
                val miss  = ArrayList<String>()
                for (code in codes) {
                    val cached = com.vermouthx.stocker.utils.StockerIntradayCache.get(code, now)
                    if (cached != null) hits[code] = cached else miss.add(code)
                }

                if (miss.isNotEmpty()) {
                    val fetched = com.vermouthx.stocker.utils.StockerQuoteHttpUtil
                        .getIntradayData(market, miss)
                    for ((code, data) in fetched) {
                        com.vermouthx.stocker.utils.StockerIntradayCache.put(code, data, now)
                        toBroadcast[code] = data
                    }
                }
                toBroadcast.putAll(hits)
            }

            if (toBroadcast.isNotEmpty()) {
                com.vermouthx.stocker.views.StockerTableView.syncAllIntradayData(toBroadcast)
            }

            intradayTickCounter++
            if (intradayTickCounter % 10L == 0L) {
                val keep = HashSet<String>()
                visible.values.forEach { keep.addAll(it) }
                keep.addAll(setting.codesByMarket(com.vermouthx.stocker.enums.StockerMarketType.AShare))
                keep.addAll(setting.codesByMarket(com.vermouthx.stocker.enums.StockerMarketType.HKStocks))
                keep.addAll(setting.codesByMarket(com.vermouthx.stocker.enums.StockerMarketType.USStocks))
                com.vermouthx.stocker.utils.StockerIntradayCache.evict(keep)
            }
        }
    }
```

- [ ] **Step 6: Reset `intradayTickCounter` when `shutdown()` is called**

Inside `shutdown()`, before `intradayExecutor.shutdownNow()`, add:

```kotlin
        intradayTickCounter = 0
```

- [ ] **Step 7: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Smoke test**

Run: `./gradlew runIde`. In Favorites tab add 30+ A-share codes (sufficient to force scrollbar). Scroll partway down. In idea.log, search for `getIntradayData` debug lines (add temporary `log.info` inside the function if needed). Confirm:
- The fetch list during a single intraday tick covers only the visible window ±5 rows.
- Re-firing the tick within the same minute hits the cache (no new HTTP).
- Scrolling and waiting up to 60 s causes newly-visible rows to be fetched.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "perf(intraday): independent 60s scheduler with viewport + minute cache"
```

---

## Task 11 — Row-level fire coalescing (§5) — optional

Wrap each per-row column write in a single `fireTableRowsUpdated`. Skip this task and close the project if any step balloons past 30 minutes.

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateListener.kt:36-66`

- [ ] **Step 1: Wrap the per-row update**

Inside the existing `synchronized(model) { for (quote in quotes) { ... } }` block, after the `val rowIndex = ...` line, change the `if (rowIndex != -1)` arm to bracket a `try/finally` that fires once:

```kotlin
                if (rowIndex != -1) {
                    try {
                        StockerTableModelUtil.setIfChanged(model, rowIndex, nameCol, displayName)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, currentCol, quote.current)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, openingCol, quote.opening)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, closeCol, quote.close)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, lowCol, quote.low)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, highCol, quote.high)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, changeCol, quote.change)
                        StockerTableModelUtil.setIfChanged(model, rowIndex, percentCol, "${quote.percentage}%")
                        StockerTableModelUtil.setIfChanged(model, rowIndex, costPriceCol, StockerNumberFormat.formatPrice(costPrice))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, holdingsCol, StockerNumberFormat.formatHoldings(holdings))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, netProfitCol,
                            StockerNumberFormat.formatNetProfit(quote.current, costPrice, holdings))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, healthCol, formatHealthBadge(code))
                        StockerTableModelUtil.setIfChanged(model, rowIndex, distanceCol,
                            FinanceDistanceAnnotator.encode(FinanceDistanceAnnotator.annotate(code, quote.current)))
                    } finally {
                        model.fireTableRowsUpdated(rowIndex, rowIndex)
                    }
                } else if (quotes.size <= size) {
                    model.addRow(buildRow(quote, displayName, costPrice, holdings, model.columnCount))
                    myTableView.clearSortState()
                }
```

The extra fire is redundant with the per-cell fires that `setIfChanged` already issues — Swing collapses paints — but it gives the table view’s sorter a single coalesced range to invalidate per row instead of 13. If profiling later shows the per-cell fires are themselves a problem, swap `setIfChanged` to a `noFire` variant; not in scope here.

- [ ] **Step 2: Build**

Run: `./gradlew buildPlugin -x test`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Smoke test**

Run: `./gradlew runIde`. Open Favorites with ~30 codes. Scroll while watching paint flicker. Compare against before by toggling the change via `git stash` if useful. The bar to ship this task is "no regression" — performance gain is hard to measure without a profiler.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateListener.kt
git commit -m "perf(table): coalesce per-row updates into one fireTableRowsUpdated"
```

---

## Final acceptance

After all tasks land:

- [ ] **`./gradlew buildPlugin`** clean.
- [ ] Smoke session: open IDE, add 20+ symbols across A/HK/US, observe in idea.log that
  - quote ticks fire every `refreshInterval` while tool window visible + IDE focused,
  - quote ticks fire ~once per minute when all markets closed,
  - quote ticks fully pause when tool window collapsed or IDE deactivated,
  - intraday ticks fire every 60 s, fetch only visible-buffer codes, hit cache within the same minute,
  - inducing connectivity failure progresses through 10s → 20s → 40s … capped at 300s and recovers on success.
- [ ] **Tag** `v1.27.0` candidate and update `CHANGELOG.md` (out of scope for this plan; reminder).

---

## Self-review notes

- Spec coverage: §1→T2+T3, §2→T4+T5, §3→T6+T7, §4→T1, §5→T11 (optional), §6→T8+T9+T10. All seven covered.
- No `TBD` / `TODO` strings.
- Types referenced match: `Result<List<StockerQuote>>` defined in Task 6 step 1 and consumed identically in step 3; `StockerIntradayCache.get/put/evict` defined in Task 8 step 1 and consumed identically in Task 10 step 5; `visibleCodesByMarket()` defined in Task 9 step 4 and consumed in Task 10 step 5; `pause()/resume()` defined in Task 2 and consumed in Task 3.
- One assumption flagged in Task 9 step 2 about the JTable field name `stockTable` and scroll-pane name; engineer must verify and substitute on read.
