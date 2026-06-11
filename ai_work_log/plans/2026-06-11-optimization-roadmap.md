# Stocker P0→P3 Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved P0→P3 roadmap: eliminate silent-refresh-death and EDT violations, add test infrastructure, surface refresh state to the user, deduplicate multi-project fetching, secure the cloud-sync key, then extend providers/alerts/sessions.

**Architecture:** Keep the existing topic-based quote flow. Harden the producer (StockerApp), marshal consumers onto the EDT, and lift the fetcher from per-project to a single application-level instance refcounted by visible tool windows. New UI strings go through StockerBundle (EN + zh_CN).

**Tech Stack:** Kotlin, IntelliJ Platform SDK 2024.1, Gradle `org.jetbrains.intellij.platform` 2.12.0, JUnit 5 + kotlin-test (new).

**Verification baseline (per CLAUDE.md):** `./gradlew compileKotlin compileJava` after every task; `./gradlew test` once Task 4 lands; `./gradlew buildPlugin` at the end. Commit per task on master (repo convention; see dfd8726).

---

### Task 1: Commit the already-verified pause/off-hours fixes

**Files:** already modified in working tree: `StockerApp.kt`, `StockerToolWindow.kt`

- [ ] Step 1: `git add` the two files, commit:
  `fix(refresh): keep refreshing while tool window visible; count index markets as open`
- [ ] Step 2: `git log -1 --stat` to confirm.

### Task 2: Top-level exception guard on scheduled tasks

**Files:** Modify `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

`scheduleAtFixedRate` permanently cancels the task on the first uncaught exception, with no log. `syncPublisher(...).syncQuotes(...)` runs listeners synchronously on this thread, so a listener bug kills refresh forever.

- [ ] Step 1: Extract tick bodies into `private fun consolidatedTick()` / `private fun intradayTick()`; the `Runnable`s become:

```kotlin
private fun createConsolidatedUpdateThread() = Runnable {
    try {
        consolidatedTick()
    } catch (e: Exception) {
        Logger.getInstance(StockerApp::class.java)
            .error("consolidated quote tick failed; refresh loop continues", e)
    }
}
```

(same shape for intraday). InterruptedException: let it reset the interrupt flag and return, don't log as error.
- [ ] Step 2: `./gradlew compileKotlin compileJava` → BUILD SUCCESSFUL.
- [ ] Step 3: Commit `fix(refresh): survive listener exceptions in scheduled ticks`.

### Task 3: EDT compliance for table-model mutations

**Files:** Modify `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateListener.kt`; inspect `StockerQuoteDeleteListener.kt`, `StockerQuoteReloadListener.kt` and fix the same way if they mutate the model off-EDT.

- [ ] Step 1: In `syncQuotes`, keep data prep (column resolution can stay, it's model-read only — move it inside too for simplicity) and wrap the whole mutation block in `SwingUtilities.invokeLater { ... }`. Capture `quotes`/`size` by closure. `synchronized(model)` stays (other writers exist).
- [ ] Step 2: Same review for delete/reload listeners; `StockerTableView.syncIndices` already uses invokeLater internally — verify, then drop the now-pointless `synchronized(myTableView)` in `syncIndices` only if verified redundant.
- [ ] Step 3: Compile; commit `fix(table): marshal quote updates onto the EDT`.

### Task 4: Test infrastructure + unit tests + CI

**Files:** Modify `build.gradle.kts`, `.github/workflows/build.yml`, `CLAUDE.md`; Create `src/test/kotlin/com/vermouthx/stocker/utils/{StockerQuoteParserTest,StockerMarketSessionTest,StockerIntradayCacheTest,StockerAShareCodeTest}.kt`

- [ ] Step 1: build.gradle.kts dependencies:

```kotlin
testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
```

plus `tasks.withType<Test> { useJUnitPlatform() }`.
- [ ] Step 2: Parser tests with real captured response fixtures (Sina A-share line incl. SH/SZ/BJ prefixes and empty-quote line; Tencent A-share line; futures truncated line → null). Session tests: A-share open at Wed 10:00 CST, closed at lunch 12:00, closed Saturday; Futures night session crossing midnight (22:00 open, 01:00 open, 03:00 closed); Crypto always open. Cache tests: same-minute hit, next-minute miss, evict retains keep-set. Prefix tests: 600519→sh, 000001→sz, 920371→bj, 430090→bj... (430090 starts with '4'→bj), sh600519 passthrough, canonical uppercase.
- [ ] Step 3: `./gradlew test` → all green. If `Logger.getInstance` breaks AShareCode test at object-init, extract `prefixedAShareCode`/`canonicalAShareCode` to pure `object StockerAShareCodes` and delegate.
- [ ] Step 4: build.yml: add `- name: Run tests` / `run: ./gradlew test` before buildPlugin. CLAUDE.md: replace the "no test source set" note with `./gradlew test`.
- [ ] Step 5: Commit `test: add unit test harness covering parser, sessions, cache, code prefixes`.

### Task 5: Refresh status indicator

**Files:** Create `src/main/kotlin/com/vermouthx/stocker/listeners/StockerRefreshStatusNotifier.kt`; Modify `StockerApp.kt`, `StockerSimpleToolWindow.kt` (or `StockerTableView` footer), `StockerToolWindow.kt`, `messages/StockerBundle.properties`, `messages/StockerBundle_zh_CN.properties`

- [ ] Step 1: Notifier:

```kotlin
enum class StockerRefreshState { LIVE, OFF_HOURS, PAUSED, BACKOFF }
data class StockerRefreshStatus(val state: StockerRefreshState, val intervalSeconds: Long, val lastSuccessAt: Instant?)
interface StockerRefreshStatusNotifier {
    fun statusChanged(status: StockerRefreshStatus)
    companion object { val TOPIC = Topic.create("StockerRefreshStatus", StockerRefreshStatusNotifier::class.java) }
}
```

- [ ] Step 2: StockerApp: `@Volatile lastSuccessAt`; publish at each consolidated tick entry/exit covering all four states (paused → PAUSED; off-hours-skip → OFF_HOURS; skipUntil active → BACKOFF; successful publish → LIVE + lastSuccessAt=now). Publish only on state/timestamp change to avoid bus spam… simpler: publish every tick, label update is cheap.
- [ ] Step 3: Footer JLabel in each tool window tab (subscribe in StockerToolWindow alongside other topics; update via invokeLater). Bundle keys: `status.live={0}s live · updated {1}`, `status.offhours=Closed · 1/min`, `status.paused=Paused`, `status.backoff=Source errors · backing off` + zh_CN translations.
- [ ] Step 4: Compile; commit `feat(ui): refresh status indicator with last-update time`.

### Task 6: UPDATE_TIME optional column

**Files:** Modify `enums/StockerTableColumn.kt`, both bundles, `StockerQuoteUpdateListener.kt` (buildRow + setIfChanged), `StockerTableView.initTable` if column list is hardcoded, settings column-picker (reads enum — verify it auto-includes).

- [ ] Step 1: Add enum entry UPDATE_TIME with bundle key `table.column.updateTime`; display `quote.updateAt` time part (`substringAfter(' ')`).
- [ ] Step 2: Not in default visible set. Compile, commit `feat(table): optional quote update-time column`.

### Task 7: Refresh interval setting + hot apply

**Files:** Modify `StockerApp.kt` (read interval inside `schedule()`), `views/windows/StockerSettingWindow.kt` (spinner 1–300s bound to `setting.refreshInterval`, apply path already restarts app), bundles (`settings.refresh.interval=Refresh interval (seconds)`).

- [ ] Steps: replace `schedulePeriod` val with a read at schedule-time; add `intTextField`/spinner row; verify apply → `shutdownThenClear(); schedule()` already runs (StockerSettingWindow:359). Compile, commit `feat(settings): configurable refresh interval, applies without restart`.

### Task 8: Application-level shared fetcher

**Files:** Modify `StockerAppManager.kt` (single shared `StockerApp`, project set, visibility refcount), `StockerToolWindow.kt` (register visibility instead of owning the app), `StockerStartupActivity.kt` (verify it doesn't create apps), actions using `myApplication(project)` keep working via delegation.

- [ ] Step 1: Manager: `private val sharedApp by lazy { StockerApp() }`, `projects: MutableSet<Project>`, `visibleProjects: MutableSet<Project>`; `myApplication(project)` returns sharedApp when project registered; `unregister` only shuts down when `projects` empties; `setToolWindowVisible(project, visible)` pauses sharedApp iff `visibleProjects` empty.
- [ ] Step 2: StockerToolWindow: drop `myApplication = StockerApp()`; use manager. `applyPauseState` → `StockerAppManager.setToolWindowVisible(project, toolWindowVisible)`. Needs project reference in the listener scope (already has, from createToolWindowContent).
- [ ] Step 3: Guard `schedule()` against double-scheduling (track `scheduled` flag) since two projects opening would call it twice.
- [ ] Step 4: Compile; grep all `StockerAppManager.` call sites and re-verify semantics; commit `refactor(lifecycle): single application-level fetcher shared across projects`.

### Task 9: PasswordSafe for cloud-sync API key

**Files:** Modify `settings/StockerSetting.kt` (apiKey accessor via `PasswordSafe.instance` + `CredentialAttributes(generateServiceName("Stocker", "cloudSync"))`; one-time migration from `myState.cloudSyncApiKey` then blank it), verify `StockerCloudSyncService`/`StockerSettingWindow` read through the accessor only.

- [ ] Steps: implement accessor, migrate-on-first-read, compile, commit `security: store cloud sync API key in PasswordSafe`.

### Task 10: Provider strategy interface + failover

**Files:** Create `api/StockerQuoteSource.kt` (interface: `host/prefix/parse/supports`), refactor `StockerQuoteProvider` to implement it (enum stays for settings serialization compatibility), split `StockerQuoteParser` per provider behind the interface; `StockerQuoteHttpUtil.get` takes the interface. Failover in StockerApp: per-market consecutive-failure counter; ≥2 failures and fallback supports market → use other provider until primary recovers (probe primary every 10 ticks).

- [ ] Keep settings storage as the enum (no migration); failover is runtime-only. Tests for parse dispatch. Compile + test, commit `refactor(provider): strategy interface + automatic source failover`.

### Task 11: Price alerts

**Files:** Modify `StockerSettingState.kt` (`stockAlertsAbove/Below: MutableMap<String, Double>`), `StockerSetting.kt` accessors, `StockerTableView.kt` popup (menu item "Set price alert…" → dialog with above/below fields), new check in `StockerQuoteUpdateListener` (on EDT batch: compare prev current vs new current crossing threshold → `StockerNotification.notifyPriceAlert(code, price)`, then clear that alert), `StockerNotification.kt`, bundles.

- [ ] One-shot semantics (alert clears after firing). Compile, commit `feat(alerts): one-shot price-cross alerts from the table popup`.

### Task 12: CN holiday table + call-auction session

**Files:** Modify `utils/StockerMarketHours.kt` (`StockerMarketSession`): add `private val CN_HOLIDAYS_2026: Set<LocalDate>` (official A-share closures), AShare/Futures `isOpen` checks holiday set; AShare first window starts 09:15. Tests in `StockerMarketSessionTest`.

- [ ] Compile + test, commit `feat(session): 2026 CN holiday calendar + 9:15 call-auction window`.

### Task 13: Status-bar mini ticker

**Files:** Create `statusbar/StockerTickerWidgetFactory.kt` + widget (subscribe STOCK_ALL_QUOTE_UPDATE_TOPIC, rotate `focusedStocks` every widget refresh, text `CODE +1.23%`), register `<statusBarWidgetFactory>` in `plugin.xml`, bundle for display name.

- [ ] Compile, commit `feat(statusbar): rotating ticker for focused stocks`.

### Task 14: i18n cleanup + legacy field removal

**Files:** Modify `StockerQuoteUpdateListener.kt` (move hardcoded zh tooltips to bundle keys `health.*`, `tooltip.*`), bundles; `StockerSettingState.kt` + `StockerSetting.kt`: delete `aShareList/hkStocksList/usStocksList/cryptoList/futuresList` write paths, keep read-only migration if `favoritesMigrated == false`.

- [ ] Compile + test + `./gradlew buildPlugin`, commit `chore: route health tooltips through bundle; retire legacy per-market lists`.

---

## Self-review notes
- Spec coverage: report P0 (3 stability items + tests) → Tasks 1–4; P1 (indicator, timestamp, interval, shared fetcher, PasswordSafe) → Tasks 5–9; P2 (provider/failover, alerts, holidays, ticker) → Tasks 10–13; P3 (i18n/legacy; god-class split explicitly deferred as incremental) → Task 14.
- Known risk: Task 8 touches every consumer of StockerAppManager — grep before commit. Task 10 is the largest; if it destabilizes, ship interface extraction first and failover as its own commit.
