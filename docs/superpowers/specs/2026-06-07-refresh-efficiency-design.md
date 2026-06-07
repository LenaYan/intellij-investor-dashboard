# Data-list refresh efficiency — design (2026-06-07)

## Goal

Reduce outbound HTTP traffic and EDT load for the Favorites / Watchlist data
lists without changing user-visible features. Targets:

- Reduce total HTTP requests by ~80 % in steady state (idle window + market off-hours dominate the day for most users).
- Cap concurrent requests against any single host to avoid Sina / Tencent / ifzq throttling.
- Keep table render stable during scroll and during partial-failure ticks.

Out of scope for this spec: switching table writes to EDT (#9), URL batching
for huge code lists (#10), response-hash caching (#11). These can be follow-ups.

## Architecture choice

**Dual scheduler.** `StockerApp` will own two `ScheduledExecutorService`s:

- `quoteExecutor` — period = `StockerSetting.refreshInterval` (default 5s),
  drives quote+indices fetch and broadcasts on `STOCK_ALL_QUOTE_UPDATE_TOPIC`.
- `intradayExecutor` — period = 60s constant, drives sparkline fetch and
  broadcasts via `StockerTableView.syncAllIntradayData`.

The two share `state: { RUNNING, PAUSED }`, the trading-hours check, and the
backoff state. Pausing is a soft gate: tasks still fire on schedule but no-op
when state is PAUSED — the executor and HTTP pool stay alive.

`StockerRefreshAction` / `StockerStopAction` semantics are unchanged.
Stop = hard `shutdown()`. Refresh = `shutdownThenClear() + schedule()`.

## §1 — Lifecycle gating (project optimization #1)

Pause refresh whenever the tool window is hidden OR the IDE is deactivated.

- `StockerToolWindow` registers two listeners on `createToolWindowContent`:
  - `ToolWindowManagerListener.stateChanged` — track this tool window’s
    `isVisible` flag.
  - `ApplicationActivationListener.applicationActivated / applicationDeactivated`
    — track IDE focus.
- Disposed in `cleanup()` together with the existing message-bus disposables.
- Combined predicate: **RUNNING iff (window visible) AND (IDE active)**.
- Implementation lives on `StockerApp`:
  - `fun pause()` — sets `state = PAUSED`. Idempotent.
  - `fun resume()` — sets `state = RUNNING`. Idempotent.
  - Both tasks’ first line: `if (state == PAUSED) return`.

Why soft pause instead of `shutdown()` + `schedule()`: avoids rebuilding the
thread pool and reopening HTTP keep-alive connections on every Tab switch.

## §2 — Trading hours (project optimization #2)

Skip quote/indices/intraday fetching when no relevant market is open.

Data structure — new `utils/StockerMarketHours.kt`:

```kotlin
enum class StockerMarketSession(
    val market: StockerMarketType,
    val zone: ZoneId,
    val windows: List<Pair<LocalTime, LocalTime>>,
) {
    AShare (StockerMarketType.AShare,   ZoneId.of("Asia/Shanghai"),
            listOf(09:30..11:30, 13:00..15:00)),
    HK     (StockerMarketType.HKStocks, ZoneId.of("Asia/Shanghai"),
            listOf(09:30..12:00, 13:00..16:00)),
    US     (StockerMarketType.USStocks, ZoneId.of("America/New_York"),
            listOf(09:30..16:00)),                     // DST handled by ZoneId
    Futures(StockerMarketType.Futures,  ZoneId.of("Asia/Shanghai"),
            listOf(09:00..11:30, 13:30..15:00, 21:00..02:30)),
    Crypto (StockerMarketType.Crypto,   ZoneId.of("UTC"),
            emptyList());                              // empty == 24/7

    fun isOpen(now: Instant): Boolean
}
```

Weekend rule: Saturday/Sunday in `Asia/Shanghai` returns `false` for all
non-Crypto markets. Holidays are intentionally **not** handled — the upstream
quote APIs simply return the last close, and at 60s cadence the cost is
negligible.

Scheduling integration:

- Both executors keep their nominal periods (`refreshInterval` / 60s).
- Quote tick computes
  `anyOpen = sessionsWhereUserHasCodes.any { it.isOpen(now) } || userHasAnyCryptoCode`
  where `sessionsWhereUserHasCodes` is the set of `StockerMarketSession`
  values for which `setting.codesByMarket(market) ∪ watchlist codes` is
  non-empty.
  - If `anyOpen` → fetch normally.
  - If `!anyOpen` → increment an internal `quoteSkipCounter`. Real HTTP runs
    only when `quoteSkipCounter % (60 / refreshInterval) == 0` — effectively
    one quote tick per minute when all markets are closed.
- Intraday tick: if `!anyOpen` → return immediately (intraday is meaningless
  outside trading hours).

## §3 — Exponential backoff on failure (project optimization #5)

Scope: **quote executor only.** Intraday backoff would leave the user staring
at empty sparklines for minutes — worse UX than continuing to try.

State on `StockerApp`:

```kotlin
@Volatile private var consecutiveFailures: Int = 0
@Volatile private var skipUntil: Instant = Instant.MIN
```

Failure definition: in a single tick, any market with non-empty `codes`
returns an empty result OR throws. To distinguish "empty because user has no
codes" from "empty because HTTP failed", `StockerQuoteHttpUtil.get(...)` is
changed to return `Result<List<StockerQuote>>` (Kotlin stdlib `Result`), so
the caller can branch on success/failure.

Algorithm — at the start of each quote tick:

```
if (now < skipUntil) return
... fetch ...
if (anyFailure) {
    consecutiveFailures++
    if (consecutiveFailures >= 3) {
        val baseSec  = setting.refreshInterval
        val factor   = 1L shl (consecutiveFailures - 2).coerceAtMost(8)
        val delaySec = min(300L, baseSec * factor)
        skipUntil = now.plusSeconds(delaySec)
        log.warn("quote backoff: failures=$consecutiveFailures, next=${delaySec}s")
    }
} else if (consecutiveFailures > 0) {
    log.info("quote recovered after $consecutiveFailures failures")
    consecutiveFailures = 0
    skipUntil = Instant.MIN
}
```

Example progression with `refreshInterval = 5`:
`5 → 5 → 5 → 10 → 20 → 40 → 80 → 160 → 300 → 300 …`.
Any single success resets to 0. No user-facing notification.

## §4 — HTTP pool tuning (project optimization #6)

`StockerHttpClientPool.kt:25` — `defaultMaxPerRoute = 10` becomes `3`.

Rationale: per-code intraday fetches all hit `web.ifzq.gtimg.cn`. A 10-wide
connection burst against one host is the most likely throttling trigger today.
After §6 (viewport trimming + cache) reduces the burst size, 3 is plenty.

`MaxTotal` is unchanged.

## §5 — Coalesce table-update fires (project optimization #7) — optional

`StockerQuoteUpdateListener.syncQuotes` currently fires up to 13 cell-update
events per row per tick. The aim is to collapse them to one row-update.

`DefaultTableModel.setValueAt` always fires its own `fireTableCellUpdated`,
which makes "truly silent" cell writes painful without reflection. Therefore
the realistic improvement is wrapping each row’s cell writes with one
`fireTableRowsUpdated(row, row)` at the end and accepting that the per-cell
events also fire — Swing will coalesce the resulting paints anyway. The real
win is **not** triggering sort invalidation on every cell.

If this turns out to require more than ~1 hour of careful work, it is dropped
from this iteration and tracked separately.

## §6 — Viewport-aware intraday + minute-keyed cache (project optimization #3)

### 6.1 Cache

New `utils/StockerIntradayCache.kt`:

```kotlin
object StockerIntradayCache {
    private data class Entry(val data: StockerIntradayData, val minuteKey: Long)
    private val map = ConcurrentHashMap<String, Entry>()

    fun get(code: String, now: Instant): StockerIntradayData? {
        val e = map[code] ?: return null
        return if (e.minuteKey == now.epochSecond / 60) e.data else null
    }
    fun put(code: String, data: StockerIntradayData, now: Instant) {
        map[code] = Entry(data, now.epochSecond / 60)
    }
    fun evict(keep: Set<String>) { map.keys.retainAll(keep) }
}
```

Freshness is keyed by natural minute (`epoch_seconds / 60`). Same-minute hits
are free; crossing a minute boundary auto-invalidates. This matches the
upstream data model — minute candlesticks update once per minute.

### 6.2 Visible-code computation

Add to `StockerTableView` companion:

```kotlin
@JvmStatic
fun visibleCodesByMarket(): Map<StockerMarketType, Set<String>>
```

Each `StockerTableView` keeps a `@Volatile var lastVisibleSnapshot:
VisibleSnapshot` (data class holding `firstRow`, `lastRow`, and per-row
`code`+`market` arrays). The snapshot is refreshed on the EDT by a
`ComponentListener` (resize), an `AdjustmentListener` on the vertical
scrollbar, and a `TableModelListener` (row count change). The 60 s intraday
task only reads this snapshot — it never touches `JTable` directly.

The snapshot computation does:

- `JTable.getVisibleRect()` → `rowAtPoint(top)`, `rowAtPoint(bottom-1)`.
- Apply ±5 buffer, clamp to `[0, rowCount)`.
- Read `SYMBOL` column for each row, classify into market via the same logic
  `StockerApp.unionCodes` uses today (split per market table).

No `ListSelectionListener` / `Scrollable` callbacks are added. The 60 s
intraday tick re-reads viewport on each fire; this is the only update path.

### 6.3 Intraday scheduler

```
intradayExecutor task (period = 60s):
  if (state == PAUSED) return
  if (no relevant market open) return

  val now = Instant.now()
  val visible = StockerTableView.visibleCodesByMarket()
  val toBroadcast = mutableMapOf<String, StockerIntradayData>()

  for ((market, codes) in visible) {
      if (market !in [AShare, HKStocks, USStocks]) continue  // crypto/futures skip

      // Split into hits and misses
      val hits = codes.mapNotNull { c -> cache.get(c, now)?.let { c to it } }
      val misses = codes - hits.map { it.first }.toSet()

      val fetched = StockerQuoteHttpUtil.getIntradayData(market, misses.toList())
      fetched.forEach { (c, d) -> cache.put(c, d, now); toBroadcast[c] = d }
      hits.forEach { (c, d) -> toBroadcast[c] = d }
  }

  if (toBroadcast.isNotEmpty()) {
      StockerTableView.syncAllIntradayData(toBroadcast)
  }

  // Periodic eviction every ~10 minutes
  if (intradayTickCounter % 10 == 0) {
      cache.evict(visible.values.flatten().toSet() + allFavoritesAndWatchlistCodes())
  }
```

### 6.4 Edge cases

- **Scrolling fast**: 6.1’s minute-key cache means within the same minute new
  rows entering viewport will fall through to a fetch on the next 60 s tick.
  User sees a sparkline appear within < 60 s of stopping the scroll.
- **Both tabs visible** (Favorites + Watchlist showing same code): two views,
  one cache entry, one HTTP request, both broadcast deliveries.
- **Very small table** (< 10 rows): `±5` buffer means viewport ≈ whole list,
  degenerating to current behavior, which is desired.
- **Code deleted from list**: stays in cache until next 10-tick evict pass,
  harmless.

### 6.5 Deliberately not in scope

`ChangeListener` on the viewport, scroll-throttle handlers, per-code LRU
priority based on view time. The 60 s tick + ±5 row buffer is the minimum
change that captures the gain.

## Risks

| Risk | Mitigation |
|---|---|
| `Result<List<StockerQuote>>` API change ripples through callers | Local; only `StockerApp.fetchQuotesIfActive` consumes it. Search confirms no other callers. |
| Soft pause inside the executor races with `shutdown()` from `StopAction` | `state` is `@Volatile`; `shutdown()` flips `refreshActive=false` first, then `shutdownNow()`. Tasks already check `refreshActive` first. |
| US DST transition during a long-running IDE session | `ZoneId.of("America/New_York") + LocalTime` re-evaluated each tick handles this. No persistent calendar. |
| Viewport read on non-EDT corrupts JTable layout | §6.2 uses a `ComponentListener`-maintained snapshot, never touches `JTable.getVisibleRect()` off EDT. |
| Cache memory growth | Bounded by `evict(keep)` every ~10 min to favorites ∪ watchlist ∪ viewports. Worst-case = total user codes (~hundreds). |

## Testing

This codebase has no test harness for the scheduling layer. Manual verification:

1. **Visibility pause**: collapse tool window for 30 s, observe no Sina /
   Tencent traffic via `tcpdump`/network log. Restore — first tick within
   `refreshInterval`.
2. **IDE deactivate**: minimize IDE, verify same.
3. **Off-hours**: launch on weekend morning, verify quote tick logs show
   "off-hours skip" once per minute and intraday is silent.
4. **Backoff**: temporarily set a wrong host in `StockerSetting.quoteProvider`,
   verify failure log at tick 3 with `delay=10s`, doubling thereafter, capped
   at 300 s, recovering immediately on restoration.
5. **Viewport intraday**: open Favorites with ~40 codes, scroll to bottom,
   verify intraday cache for visible+buffer codes is fetched, off-screen codes
   are NOT fetched (verify via debug log of `misses.size`).
6. **HTTP pool**: with a deliberate slow upstream, verify no more than 3
   concurrent connections to `web.ifzq.gtimg.cn`.

## Migration / rollback

All changes are additive. To roll back any single optimization:

- §1: remove the two listener registrations in `StockerToolWindow`; soft-pause
  state stays RUNNING forever, behaviour reverts.
- §2: short-circuit `isOpen(now)` to always `true`.
- §3: clamp `consecutiveFailures = 0`.
- §4: revert single number in `StockerHttpClientPool`.
- §5: drop the row-update wrap.
- §6: have intraday tick call `getIntradayData(market, allCodes)` instead of
  `misses`. Cache becomes a free-floating optimization.

## Order of implementation

Recommended PR/commit order — each piece independently shippable:

1. §4 (one line). Safety first.
2. §1 (pause/resume + listeners). Biggest single-step idle-time win.
3. §2 (market hours). Compounds with §1 for nights/weekends.
4. §3 (backoff). Now that §1+§2 reduce traffic, backoff catches the remaining
   transient failures.
5. §6 (intraday scheduler refactor + cache + viewport). Largest change, gated
   behind everything above.
6. §5 (fire coalescing). Polish; optional if time-boxed out.
