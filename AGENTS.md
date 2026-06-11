# IntelliJ Investor Dashboard Agent Guide

## Project Overview

- This repository contains the `Stocker` JetBrains plugin (`com.vermouthx.intellij-investor-dashboard`).
- Sources are Kotlin-only under `src/main/kotlin`; the former Java tree was fully migrated.
- The plugin targets IntelliJ Platform `2024.1+` (since-build `241`) via the `org.jetbrains.intellij.platform` Gradle plugin. Bytecode targets Java 17 — don't raise it without bumping since-build.

## Repository Layout

- `src/main/kotlin/com/vermouthx/stocker`
  - `actions`: toolbar and Tools menu actions
  - `activities`: startup hooks (`StockerStartupActivity`)
  - `api`: quote-source strategy interface (`StockerQuoteSource`)
  - `components`: table model and cell renderers (sparkline, health, quote colors)
  - `entities`, `enums`: quote/intraday data classes, market/provider/column enums
  - `finance`: self-contained finance/ subsystem — status-bar widget, watchlist file
    watcher, daily coordinator, scenario tree, entry timing, news radar. Own state
    (`FinanceState`), own notifier (`FinanceNotifier`), own panels. Do not entangle it
    with the quote-flow message bus; `StockerApp` and `StockerQuoteUpdateListener` are
    the deliberate bridge points.
  - `listeners`: message-bus listeners for quote update/reload/delete and refresh status
  - `notifications`: welcome, release-note, and price-alert notifications
  - `services`: cloud sync
  - `settings`: persistent plugin settings (`StockerSetting`, unified favorites list)
  - `statusbar`: focused-stock rotating ticker widget
  - `utils`: HTTP, parsers, market sessions, intraday cache, helper logic
  - `views`, `views/dialogs`, `views/windows`: table view + extracted collaborators
    (`StockerIndexPanel`, `StockerTableRowPopup`, `StockerTableSortController`), dialogs,
    tool windows
- `src/test/kotlin`: JUnit 5 unit tests for pure logic only (parsers, sessions, cache,
  code prefixes, settings cascade) — no IDE fixtures
- `src/main/resources`
  - `META-INF/plugin.xml`: plugin registration and action declarations
  - `messages/*.properties`: localized strings (`StockerBundle` + `_zh_CN`)
  - `icons/`: plugin assets

## Working Rules

- Prefer small, surgical fixes. This plugin has a lot of event-driven UI behavior; broad rewrites are risky.
- User-visible text goes through `messages/StockerBundle*.properties` — never hardcode it; keep `_zh_CN` in sync.
- No raw `Thread(...)` / `Thread.sleep(...)`; use platform threading (`ApplicationManager.invokeLater`, `AppExecutorUtil`, scheduled executors owned by `StockerApp`).
- When changing plugin wiring, actions, startup behavior, settings registration, status-bar widgets, or notification groups, verify `src/main/resources/META-INF/plugin.xml`.
- When changing tool window, table, or popup behavior, review both sides of the flow:
  - UI event handling in `views` / `components`
  - message-bus update/delete/reload listeners in `listeners`
- Background work tied to a project must hook into the `StockerAppManager` lifecycle (register/unregister), not a singleton.
- When changing settings-backed behavior, confirm both persistence and immediate UI refresh behavior.
- New pure logic should come with unit tests under `src/test/kotlin`; UI/IDE-coupled code is compile-verified only.

## Verification

- Default verification for code changes:
  - `./gradlew compileKotlin compileJava test`
- For broader plugin or packaging changes, consider:
  - `./gradlew buildPlugin` and `./gradlew verifyPlugin`
- If the change affects UI behavior, context menus, notifications, actions, or settings application, note whether the fix was only compile-verified or manually exercised in IntelliJ (`./gradlew runIde`).

## Release And Versioning

- `pluginVersion` lives in `gradle.properties`.
- `build.gradle.kts` uses `CHANGELOG.md` as the source for plugin change notes shown on release.
- `StockerNotification.kt` contains the in-product release note content shown to users after upgrade.
- When bumping the plugin version, you must update these files together in the same change:
  - `gradle.properties`
  - `CHANGELOG.md`
  - `src/main/kotlin/com/vermouthx/stocker/notifications/StockerNotification.kt`

## Common Pitfalls

- Right-click or popup-menu behavior can break if selection/focus changes are not accounted for.
- `DefaultTableModel.setValueAt` fires `fireTableCellUpdated` itself — never add manual fires on top (`StockerTableModelUtil.setIfChanged` is the canonical guarded write).
- Quote producers and table listeners are linked only by message-bus topics, not direct calls; touching one side usually requires inspecting the other.
- Localization regressions are easy to introduce when labels/descriptions exist in both action declarations and runtime UI code.
- Settings changes should not silently require restart unless the behavior is explicitly designed that way (`StockerApp.schedule()` re-reads the refresh interval on the shutdown+schedule cycle).
- A-share row identity is the canonical prefixed code (`SH600519`); bare 6-digit codes collide across exchanges.
