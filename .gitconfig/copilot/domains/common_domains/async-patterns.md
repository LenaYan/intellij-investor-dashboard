# Async Patterns

Scope:
- Governs threading, background task execution, and concurrency in the Stocker plugin.

## Goal
- Ensure responsive UI while performing network I/O and data processing safely.

## Rules
- **EDT (Event Dispatch Thread)** is for UI updates only — never perform I/O or heavy computation on EDT
- Background work uses one of these approved patterns:
  1. `ApplicationManager.getApplication().executeOnPooledThread { }` — fire-and-forget pooled execution
  2. `ApplicationManager.getApplication().invokeLater { }` — schedule work on EDT from background
  3. `ProgressManager.getInstance().run(Task.Backgroundable(...))` — long tasks with progress indicator
  4. `ScheduledExecutorService` for periodic quote refresh (managed lifecycle)
- All background-to-UI transitions must use `invokeLater` or `SwingUtilities.invokeLater`
- Quote refresh timer must be cancellable — store the `ScheduledFuture` reference
- HTTP requests (quote fetching, suggestion fetching) must run off-EDT
- Connection pooling is managed by `StockerHttpClientPool` — never create raw `HttpURLConnection` instances
- Cancellation: long-running tasks must check `ProgressIndicator.isCanceled` or handle `InterruptedException`
- Plugin disposal: all scheduled tasks must be cancelled in `Disposer` lifecycle callbacks

## Thread Safety
- `StockerSettingState` is read from multiple threads — access through `StockerSetting.instance`
- Table model updates must happen on EDT (Swing threading rule)
- Message-bus event delivery happens on the publishing thread — listeners that update UI must dispatch to EDT

## Anti-Patterns
- Using `Thread()` or `Thread.sleep()` directly
- Performing network I/O on EDT (freezes the IDE)
- Fire-and-forget background tasks without error handling (silent failures)
- Accessing Swing components from a background thread
- Creating unbounded thread pools (use IntelliJ's managed pools)
- Not cancelling scheduled tasks on plugin unload (resource leak)

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt`
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerHttpClientPool.kt`
- `src/main/kotlin/com/vermouthx/stocker/StockerAppManager.kt`
