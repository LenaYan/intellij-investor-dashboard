# Storage and Cache

Scope:
- Governs how plugin settings are persisted and how data caching works in Stocker.

## Goal
- Reliable persistence of user preferences with safe concurrent access.

## Rules
- Plugin settings use IntelliJ `PersistentStateComponent<StockerSettingState>` pattern
- Settings singleton access: `StockerSetting.instance` (application-level service)
- State class (`StockerSettingState`) is a plain data holder with `@Tag` and `@Attribute` annotations for XML serialization
- All fields in state class must have sensible defaults (plugin must work on first install with no saved state)
- Settings are stored in IntelliJ's config directory — never write custom files to the filesystem
- State modifications must trigger UI refresh via message bus (settings change → republish quotes)
- Stock lists (favorites per market type) are persisted in `StockerSettingState` as `MutableList<String>`
- Custom stock names map is persisted as `MutableMap<String, String>`

## Concurrency Safety
- `StockerSettingState` is accessed from both EDT and background threads
- IntelliJ's `PersistentStateComponent` handles serialization thread-safety
- Modifications to stock lists should be done atomically (copy-on-write or synchronized block)
- Never hold a long-lived reference to a mutable list from settings — copy it for iteration

## Cache Policy
- No explicit disk cache — quote data is transient (in-memory table model only)
- Stale quote data is acceptable between refresh intervals
- On IDE restart: tables are empty until first refresh completes
- Suggestion results are not cached — each search triggers a fresh HTTP call

## Anti-Patterns
- Writing files directly to the user's home directory or project directory
- Storing large data blobs in `PersistentStateComponent` (it's XML-serialized)
- Reading settings without going through `StockerSetting.instance`
- Mutating the settings state object without triggering a save (`settingsState.incrementModificationCount()`)
- Caching quote data across IDE restarts (data goes stale immediately)

## Verified Against
- `src/main/kotlin/com/vermouthx/stocker/settings/StockerSetting.kt`
- `src/main/kotlin/com/vermouthx/stocker/settings/StockerSettingState.kt`
