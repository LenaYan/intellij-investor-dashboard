# Error Handling

Scope:
- Governs how errors are propagated, logged, and surfaced to users in the Stocker plugin.

## Goal
- Graceful degradation: network failures should never crash the IDE or leave the UI in a broken state.

## Rules
- Network errors (timeout, DNS failure, HTTP 4xx/5xx) must be caught and logged — never propagated as unhandled exceptions
- Use IntelliJ Platform logging: `com.intellij.openapi.diagnostic.Logger` (not `println` or `System.err`)
- Logger instance: `private val LOG = Logger.getInstance(ClassName::class.java)`
- User-visible error feedback:
  - Transient network errors: show in IDE status bar or notification balloon, not modal dialogs
  - Configuration errors: show in Settings UI with validation message
  - Fatal plugin errors: use `Notification` with `NotificationType.ERROR`
- All notification text must come from `StockerBundle` — never hardcoded strings
- HTTP response parsing must handle malformed JSON/data gracefully (return empty results, not exceptions)
- Quote provider fallback: if primary provider fails, log warning and retry — do not switch providers silently

## Error Categories

| Category | Handling | User feedback |
|----------|----------|---------------|
| Network timeout | Catch, log WARN, retry once | Status bar hint |
| Malformed response | Catch, log ERROR, return empty | None (silent) |
| Provider unavailable | Catch, log ERROR | Notification balloon |
| Plugin state corruption | Catch, log ERROR, reset to defaults | Notification with action |

## Anti-Patterns
- Catching `Exception` broadly without logging (`catch (e: Exception) { }`)
- Showing stack traces to users in notification balloons
- Using `e.printStackTrace()` instead of platform Logger
- Throwing runtime exceptions from background threads (crashes the pool)
- Ignoring `IOException` in HTTP utility methods
- Showing modal error dialogs for recoverable network issues

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt`
- `src/main/kotlin/com/vermouthx/stocker/notifications/StockerNotification.kt`
