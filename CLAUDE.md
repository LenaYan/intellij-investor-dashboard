# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

`Stocker` — a JetBrains IDE plugin (`com.vermouthx.intellij-investor-dashboard`) that shows a real-time investor dashboard inside IntelliJ IDEs. Targets IntelliJ Platform `2024.1+` (since-build `241`) via the `org.jetbrains.intellij.platform` Gradle plugin. Sources are now **Kotlin-only** (`src/main/kotlin`); there is no longer a `src/main/java` tree, even though `AGENTS.md` still describes a mixed layout.

## Build / Run / Verify

The default verification before claiming a change works:

```bash
./gradlew compileKotlin compileJava       # fast compile check (Java task remains for the `java` plugin)
./gradlew test                            # JUnit 5 unit tests under src/test/kotlin (parser, sessions, cache, code prefixes)
./gradlew buildPlugin                     # produce distributable .zip in build/distributions
./gradlew runIde                          # launch sandbox IDE with the plugin installed
./gradlew verifyPlugin                    # IntelliJ plugin compatibility verifier
```

Notes specific to this build:
- Tests target pure logic only (no IDE fixture). Bytecode targets Java 17 (`-release 17` / `jvmTarget 17`) because since-build 241 IDEs and the platform test runtime run on JBR 17 — don't raise it without bumping since-build.
- `buildSearchableOptions = false` is set in `build.gradle.kts`, so don't be surprised that searchable index isn't generated.
- `gradle.properties` pins `platformType=IC` / `platformVersion=2024.1`; bumping these affects the verifier matrix configured in `build.gradle.kts`.

## Releasing / Version Bumps

These three files must move together in a single change — `build.gradle.kts` reads `CHANGELOG.md` to render the marketplace change notes, and `StockerNotification.kt` shows in-product release notes after upgrade:

1. `gradle.properties` → `pluginVersion`
2. `CHANGELOG.md` → new `[x.y.z]` section (the `org.jetbrains.changelog` plugin parses this)
3. `src/main/kotlin/com/vermouthx/stocker/notifications/StockerNotification.kt`

## High-level Architecture

### Per-project app lifecycle

- `StockerStartupActivity` (post-startup) constructs a `StockerApp` per opened project, registers it in the global `StockerAppManager` map, and starts its scheduled refresh executors.
- `StockerAppManager.StockerProjectManagerListener` (registered as a `projectListener` in `plugin.xml`) tears the app down on `projectClosing` — this calls `shutdownThenClear()` to stop executors. If you add new background work tied to a project, hook into this lifecycle, not into a singleton.

### Quote-flow message bus

The dashboard is event-driven. Three message-bus topics (`Topic.create(...)`) connect background fetchers to the UI:

- `StockerQuoteUpdateNotifier` — new quote payloads arrived from a provider; consumers: `StockerQuoteUpdateListener`, `WatchlistQuoteUpdateListener`, `FavoritesQuoteUpdateListener`.
- `StockerQuoteDeleteNotifier` — a stock was removed; consumers update tables + persistent settings.
- `StockerQuoteReloadNotifier` — full refresh (e.g. settings change, market switch).

When you touch table/popup/refresh behavior you almost always need to inspect **both** the producer side (action / scheduled task) and the relevant listener — they are linked only by topic, not by direct calls. `WatchlistQuoteUpdateListener` and `FavoritesQuoteUpdateListener` are two parallel consumers because the tool window and the Manage dialog each render their own table.

### Table / view layer

- `views/StockerTableView.kt` is the shared sortable/right-clickable table widget.
- `components/StockerTableModel.kt` is a `DefaultTableModel`; many operations already fire table events internally. Don't pile on a second `fireTableRowsUpdated` unless you've confirmed the model didn't already emit one (recent perf commits — see `git log` — explicitly coalesced these).
- `components/StockerSparklineCellRenderer.kt` and `StockerDefaultTableCellRender.kt` drive per-cell rendering; the recent `feat(intraday)` work attached a per-view "visible row snapshot" so the sparkline executor only fetches data for rows actually on screen.
- Settings-driven column visibility lives in `settings/` and is read on every render — changes there must trigger a UI refresh (typically via `StockerQuoteReloadNotifier`).

### Finance subsystem (`finance/`)

A large, mostly self-contained set of files under `com/vermouthx/stocker/finance/` powers a status-bar widget (`FinanceStatusBarWidgetFactory`, registered in `plugin.xml`) and a separate tool-window UI (`panels/FinanceToolWindowPanel.kt`). It includes a watchlist writer, file watcher, daily coordinator, scenario tree, news radar, etc. This subsystem is **not** described in `AGENTS.md`. Treat it as its own bounded context: it has its own state objects (`FinanceState`, `FinancePortfolio`, …), its own notifier (`FinanceNotifier`), and its own panels. Do not entangle it with the quote-flow message bus above unless you specifically need to.

### Settings, localization, plugin registration

- All user-visible text must go through `StockerBundle` (resource bundle `messages.StockerBundle`, with `_zh_CN` translation). Action labels in `plugin.xml` use `%key` references against the same bundle — keep both in sync.
- Anything that becomes an Action, Service, Listener, Tool Window, Configurable, NotificationGroup, or StatusBarWidget **must** be declared in `src/main/resources/META-INF/plugin.xml` or it won't be wired up at runtime.

## Hard Rules (from `.github/copilot-instructions.md`)

- English-only identifiers and comments.
- No hardcoded user-visible strings — route through `StockerBundle`.
- No raw `Thread(...)` / `Thread.sleep(...)`. Use IntelliJ Platform threading (`ApplicationManager.invokeLater`, `ProgressManager`, `AppExecutorUtil`, Kotlin coroutines on the IntelliJ dispatcher).
- New Actions / Services / Listeners must be registered in `plugin.xml`.
- Markdown reports / analysis / work-log files default to `ai_work_log/` at repo root.

## When to read the deeper guides

- `AGENTS.md` — repository working rules, common pitfalls, version-bump checklist. **Caveat:** its claim that Java holds table rendering and listeners is stale; everything is Kotlin now.
- `.github/copilot-instructions.md` — index of domain contracts under `.gitconfig/copilot/domains/common_domains/` (naming, module boundaries, async patterns, error handling, UI state, service surfaces, storage, testing, dependency policy). Load a domain file only when the task touches that domain.
- `.github/agents/*.agent.md` — task-specific agent prompts (compliance, scaffolding, test coverage, dependency security, domain-contract scaffolding).
