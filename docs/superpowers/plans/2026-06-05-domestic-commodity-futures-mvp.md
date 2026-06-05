# Domestic Commodity Futures (主连) MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users add domestic commodity-futures continuous contracts (主连) — e.g. `LH0` 生猪, `SR0` 白糖, `JD0` 鸡蛋 — as a fifth market type alongside CN/HK/US/Crypto, fetched from Sina, surfaced in Favorites table and Management/Batch-Add/Search dialogs.

**Architecture:** Add `Futures` to `StockerMarketType`, extend Sina's `providerPrefixMap` with `nf_`, write a Sina futures field parser keyed on 昨结算 as the change baseline, plumb a new `futuresList` setting + per-market topic + management UI tab. Suggest search falls back to a hardcoded ~46-entry whitelist of common 主连 contracts (Sina's suggest API has spotty futures coverage). Tencent left out — single-provider MVP. No intraday sparkline for futures (Sina's minute endpoint doesn't take `nf_` codes uniformly).

**Tech Stack:** Kotlin 2.2.21, IntelliJ Platform 2024.1 / Gradle plugin 2.12.0, Apache HttpClient (already pooled), no test framework in repo — verification is `./gradlew build` + Sandbox `./gradlew runIde` + a one-off main() smoke runner per parser change.

**Verification approach (no test infra exists):** Each task with non-trivial logic ships a `main()` smoke harness in a scratch file under `src/main/kotlin/.../scratch/` that the engineer runs via `./gradlew runIde --args=...` or by adding a temporary Run Configuration. The scratch files are deleted in the final cleanup task. Compilation is the primary regression gate.

---

## File Structure

**Modify:**
- `src/main/kotlin/com/vermouthx/stocker/enums/StockerMarketType.kt` — add `Futures` enum value.
- `src/main/kotlin/com/vermouthx/stocker/enums/StockerQuoteProvider.kt` — add `Futures to "nf_"` to Sina prefix map (Tencent: not supported).
- `src/main/kotlin/com/vermouthx/stocker/settings/StockerSettingState.kt` — add `futuresList: MutableList<String>`.
- `src/main/kotlin/com/vermouthx/stocker/settings/StockerSetting.kt` — accessor, `containsCode`, `marketOf`, `removeCode`, `allStockListSize`.
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteParser.kt` — `StockerMarketType.Futures` branches in `parseSinaQuoteFields`; Tencent throws/returns empty.
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt` — `providerSymbol` branch for Futures; `getIntradayData` skips Futures.
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerActionUtil.kt` — `Futures` branches in `addStock` and `removeStock`.
- `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateNotifier.kt` — add `FUTURES_QUOTE_UPDATE_TOPIC`.
- `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteReloadNotifier.kt` — add `STOCK_FUTURES_QUOTE_RELOAD_TOPIC`.
- `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteDeleteNotifier.kt` — add `FUTURES_QUOTE_DELETE_TOPIC`.
- `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt` — fetch+publish for `setting.futuresList`; clear futures reload topic on `clear()`.
- `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerManagementDialog.kt` — add "Futures" tab.
- `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerBatchAddDialog.kt` — add "Futures (主连)" to market combo, no prefix canonicalization needed.
- `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerSuggestionDialog.kt` — add `FUTURES` search mode backed by `StockerFuturesWhitelist`.
- `src/main/resources/messages/StockerBundle.properties` + `StockerBundle_zh_CN.properties` — labels.
- `gradle.properties` — bump `pluginVersion` to `1.26.0`.
- `CHANGELOG.md` — add 1.26.0 section.

**Create:**
- `src/main/kotlin/com/vermouthx/stocker/utils/StockerFuturesWhitelist.kt` — hardcoded map `code → 中文名` for ~46 主连 contracts + substring suggest function.
- `src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt` — `main()` runner with captured response fixtures (created in Task 4, deleted in Task 14).

---

## Reference: Sina `nf_` futures response format

URL: `GET https://hq.sinajs.cn/list=nf_LH0,nf_SR0,nf_JD0` with header `Referer: https://finance.sina.com.cn`.

Response (one var per code, comma-separated string in GBK; HttpClient picks up `Content-Type` charset, falls back to `EntityUtils.toString(entity, "UTF-8")`):

```
var hq_str_nf_LH0="生猪主连,150418,11930.000,12005.000,11780.000,11780.000,11775.000,11780.000,11780.000,11900.000,12030.000,23,31,186171.000,137021,大,生猪,2026-06-05,1,...";
```

Field indices in the raw `var hq_str_nf_<CODE>="..."` payload (i.e. before the parser prepends the code on line 33 of `StockerQuoteParser.kt`). Best-effort mapping per multiple Sina API references; **the implementer must confirm against a live capture before locking in the parser** (Task 4 Step 5 is structured to make this a print-and-eyeball check):

| raw idx | meaning              | LH0 sample  | SR0 sample  | JD0 sample  |
|---------|----------------------|-------------|-------------|-------------|
| 0       | 中文名               | 生猪主连     | 白糖主连     | 鸡蛋主连     |
| 1       | 当日时间 HHMMSS       | 150418      | 150000      | 150418      |
| 2       | 开盘                 | 11930       | 5375        | 4662        |
| 3       | 最高                 | 12005       | 5390        | 4728        |
| 4       | 最低                 | 11780       | 5316        | 4603        |
| 5       | 买价 (bid)           | 11780       | 5341        | 4699        |
| 6       | **昨结算** (涨跌基准) | 11775       | 5339        | 4699        |
| 7       | 卖价 (ask)           | 11780       | 5340        | 4700        |
| 8       | **最新价** (current) | 11780       | 5341        | 4699        |
| 9       | 今结算               | 11900       | 5348        | 4658        |
| 10      | 昨收                 | 12030       | 5403        | 4724        |
| 11      | 买量                 | 23          | 4           | 25          |
| 12      | 卖量                 | 31          | 45          | 192         |
| 13      | 持仓                 | 186171      | 672582      | 297607      |
| 14      | 成交量               | 137021      | 521214      | 547101      |
| 15      | 交易所简称           | 大          | 郑          | 大          |
| 16      | 品种短名             | 生猪         | 白糖         | 鸡蛋         |
| 17      | 当日日期 YYYY-MM-DD   | 2026-06-05  | 2026-06-05  | 2026-06-05  |

**Change baseline = raw field [6] (昨结算)** — Chinese futures convention. `change = current − prevSettle`; `percentage = change / prevSettle × 100`.

⚠️  Multiple field orderings circulate online for Sina's `nf_` endpoint. The numbers above were derived by aligning the three fixtures against expected behaviour (open ≤ high, open ≥ low, current near bid/ask). Task 4 Step 5 prints all derived columns so the implementer can spot-check open/high/low/current against the contract's official quote page on `https://finance.sina.com.cn/futures/` before committing.

**Index mapping in code:** because `parseSinaQuoteResponse` prepends `"${code},"` (line 33 of `StockerQuoteParser.kt`), the parser sees `textArray[i] = raw[i-1]` once it splits. So in the parser branch: `name = textArray[1]`, `time = textArray[2]`, `prev_settle = textArray[7]`, `current = textArray[9]`.

If the URL returns an empty string for an unknown code, Sina still emits the `var ... = "";` line — the existing parser's `if (quote.isBlank()) return@mapNotNull null` already handles it.

---

## Task 1: Add `Futures` to `StockerMarketType`

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/enums/StockerMarketType.kt`

- [ ] **Step 1: Add the enum constant**

Open the file and replace:

```kotlin
package com.vermouthx.stocker.enums

enum class StockerMarketType(val title: String) {
    AShare("CN"),
    HKStocks("HK"),
    USStocks("US"),
    Crypto("Crypto")
}
```

with:

```kotlin
package com.vermouthx.stocker.enums

enum class StockerMarketType(val title: String) {
    AShare("CN"),
    HKStocks("HK"),
    USStocks("US"),
    Crypto("Crypto"),
    Futures("Futures")
}
```

- [ ] **Step 2: Run build to surface every `when (marketType)` that needs a new branch**

Run: `./gradlew compileKotlin`
Expected: Compilation fails listing every `when` over `StockerMarketType` that is now non-exhaustive. Those are the modification targets for Tasks 3, 4, 5, 8, 9. Capture the list; do not commit yet.

- [ ] **Step 3: Commit (intentionally broken — gated by remaining tasks in the same PR/branch)**

```bash
git add src/main/kotlin/com/vermouthx/stocker/enums/StockerMarketType.kt
git commit -m "feat(futures): add Futures market type enum"
```

---

## Task 2: Add `nf_` prefix to Sina provider

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/enums/StockerQuoteProvider.kt`

- [ ] **Step 1: Add Futures prefix to SINA, leave TENCENT untouched**

Replace the `SINA` `providerPrefixMap` block to:

```kotlin
    SINA(
        titleKey = "provider.sina",
        host = "https://hq.sinajs.cn/list=",
        suggestHost = "https://suggest3.sinajs.cn/suggest/key=",
        providerPrefixMap = mapOf(
            StockerMarketType.AShare to "",
            StockerMarketType.HKStocks to "hk",
            StockerMarketType.USStocks to "gb_",
            StockerMarketType.Crypto to "btc_",
            StockerMarketType.Futures to "nf_"
        )
    ),
```

TENCENT's map stays unchanged — `providerPrefixMap[Futures]` returns null and the early-out in `StockerQuoteHttpUtil.get` already logs `"Provider Tencent does not support market type Futures"` and returns `emptyList()`. That is the desired behavior for the MVP.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: Still fails on the parser/util `when` blocks from Task 1 — but no new errors in this file.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/enums/StockerQuoteProvider.kt
git commit -m "feat(futures): map Sina nf_ prefix for Futures market"
```

---

## Task 3: Persist `futuresList` in settings

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/settings/StockerSettingState.kt`
- Modify: `src/main/kotlin/com/vermouthx/stocker/settings/StockerSetting.kt`

- [ ] **Step 1: Add the state field**

In `StockerSettingState.kt` after the `cryptoList` line (line 19), add:

```kotlin
    var futuresList: MutableList<String> = mutableListOf()
```

So the block reads:

```kotlin
    var aShareList: MutableList<String> = mutableListOf()
    var hkStocksList: MutableList<String> = mutableListOf()
    var usStocksList: MutableList<String> = mutableListOf()
    var cryptoList: MutableList<String> = mutableListOf()
    var futuresList: MutableList<String> = mutableListOf()
```

PersistentStateComponent serializes new fields with their default — existing users' `stocker-config.xml` simply gets `<futuresList />` added on first save. No migration needed.

- [ ] **Step 2: Add `futuresList` accessor in `StockerSetting`**

In `StockerSetting.kt` after the `cryptoList` accessor block (around line 115), add:

```kotlin
    var futuresList: MutableList<String>
        get() = myState.futuresList
        set(value) {
            myState.futuresList = value
        }
```

- [ ] **Step 3: Update `allStockListSize`, `containsCode`, `marketOf`, `removeCode`**

Replace `allStockListSize` (line 179-180) with:

```kotlin
    val allStockListSize: Int
        get() = aShareList.size + hkStocksList.size + usStocksList.size + cryptoList.size + futuresList.size
```

Replace `containsCode` (lines 254-259) with:

```kotlin
    fun containsCode(code: String): Boolean {
        return aShareList.contains(code) ||
                hkStocksList.contains(code) ||
                usStocksList.contains(code) ||
                cryptoList.contains(code) ||
                futuresList.contains(code)
    }
```

Replace `marketOf` (lines 261-275) with:

```kotlin
    fun marketOf(code: String): StockerMarketType? {
        if (aShareList.contains(code)) {
            return StockerMarketType.AShare
        }
        if (hkStocksList.contains(code)) {
            return StockerMarketType.HKStocks
        }
        if (usStocksList.contains(code)) {
            return StockerMarketType.USStocks
        }
        if (cryptoList.contains(code)) {
            return StockerMarketType.Crypto
        }
        if (futuresList.contains(code)) {
            return StockerMarketType.Futures
        }
        return null
    }
```

In `removeCode` (lines 277-303), add after the `Crypto` branch (line 301) before the closing `}`:

```kotlin
            StockerMarketType.Futures -> {
                synchronized(futuresList) {
                    futuresList.remove(code)
                }
            }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors remaining only in `StockerQuoteParser.kt`, `StockerQuoteHttpUtil.kt`, `StockerActionUtil.kt`, `StockerApp.kt`, `StockerManagementDialog.kt`, `StockerBatchAddDialog.kt`. None in settings files.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/settings/StockerSettingState.kt src/main/kotlin/com/vermouthx/stocker/settings/StockerSetting.kt
git commit -m "feat(futures): persist futuresList and route market lookups through it"
```

---

## Task 4: Parse Sina futures response

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteParser.kt`
- Create: `src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt`

- [ ] **Step 1: Add the `Futures` branch in `parseSinaQuoteFields`**

In `StockerQuoteParser.kt`, inside the `when (marketType)` block of `parseSinaQuoteFields` (line 47), add a new branch immediately after the `StockerMarketType.Crypto` branch (after line 152, before the closing `}` of the `when`):

```kotlin
                StockerMarketType.Futures -> {
                    // Sina nf_ contracts: textArray[0] is "nf_LH0" — strip the prefix so the
                    // canonical row key is "LH0" (what the user typed and stored).
                    // textArray index = rawIndex + 1 because parseSinaQuoteResponse prepends
                    // "${code}," to the splittable string (see line 33).
                    val code = textArray[0].removePrefix("nf_").uppercase()
                    val name = textArray[1]                        // raw [0]
                    val time = textArray[2]                        // raw [1] HHMMSS
                    val opening = textArray[3].toDouble()          // raw [2]
                    val high = textArray[4].toDouble()             // raw [3]
                    val low = textArray[5].toDouble()              // raw [4]
                    // Chinese futures convention: change is measured against 昨结算 (prev settle,
                    // raw [6]), not 昨收 (prev close, raw [10]).
                    val close = textArray[7].toDouble()            // raw [6] = 昨结算
                    val current = textArray[9].toDouble()          // raw [8] = 最新价
                    val change = (current - close).twoDigits()
                    val percentage = if (close != 0.0) ((current - close) / close * 100).twoDigits() else 0.0
                    val date = textArray[18]                       // raw [17] YYYY-MM-DD
                    val updateAt = formatFuturesTimestamp(date, time)
                    StockerQuote(
                        code = code,
                        name = name,
                        current = current,
                        opening = opening,
                        close = close,
                        low = low,
                        high = high,
                        change = change,
                        percentage = percentage,
                        updateAt = updateAt
                    )
                }
```

- [ ] **Step 2: Add the timestamp helper**

In the same file (`StockerQuoteParser.kt`), inside the `object StockerQuoteParser` block, after the `twoDigits()` helper at the top (line 12-14), add:

```kotlin
    private fun formatFuturesTimestamp(date: String, hhmmss: String): String {
        // date is "2026-06-05", hhmmss is "150418" (6 digits) or "90418" (5, missing leading zero).
        val padded = hhmmss.padStart(6, '0')
        val hh = padded.substring(0, 2)
        val mm = padded.substring(2, 4)
        val ss = padded.substring(4, 6)
        return "$date $hh:$mm:$ss"
    }
```

- [ ] **Step 3: Handle Futures in the Tencent parser explicitly**

In the same file, inside `parseTencentQuoteResponse`'s `when (marketType)` for `code` extraction (around line 160-167), add a branch returning empty:

```kotlin
                    val code = when (marketType) {
                        // Keep sh/sz/bj prefix; see comment in parseSinaQuoteFields.
                        StockerMarketType.AShare ->
                            text.subSequence(2, text.indexOfFirst { c -> c == '=' }).toString()
                        StockerMarketType.HKStocks, StockerMarketType.USStocks -> text.subSequence(4,
                            text.indexOfFirst { c -> c == '=' })
                        StockerMarketType.Crypto -> ""
                        StockerMarketType.Futures -> return@mapNotNull null
                    }
```

Then also patch the `updateAt` `when` (around line 182-197):

```kotlin
                    val updateAt = when (marketType) {
                        StockerMarketType.AShare -> {
                            // ...unchanged...
                        }
                        StockerMarketType.HKStocks -> {
                            // ...unchanged...
                        }
                        StockerMarketType.USStocks -> textArray[31]
                        StockerMarketType.Crypto -> ""
                        StockerMarketType.Futures -> ""
                    }
```

(The `return@mapNotNull null` above prevents reaching this `when`, but the compiler needs exhaustiveness.)

- [ ] **Step 4: Create the smoke runner**

Create file `src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt`:

```kotlin
package com.vermouthx.stocker.scratch

import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import com.vermouthx.stocker.utils.StockerQuoteParser

/**
 * Scratch runner — not wired into production. Run by right-clicking → Run 'FuturesParserSmokeKt'
 * in IntelliJ. Verifies the parser column-by-column against the captured fixture; the implementer
 * cross-checks the printed values against the official Sina page for each contract before locking
 * in the field indices. Deleted in Task 14 before merge.
 *
 * If a column reads wrong (e.g. `current` < `low`), re-read the field-index reference in the plan
 * and shift indices by one — most off-by-ones in Sina nf_ format come from confusing 昨结算 with
 * 昨收 or counting the leading code position differently.
 */
fun main() {
    // Captured 2026-06-05 from
    //   curl 'https://hq.sinajs.cn/list=nf_LH0,nf_SR0,nf_JD0' -H 'Referer: https://finance.sina.com.cn'
    val fixture = """
        var hq_str_nf_LH0="生猪主连,150418,11930.000,12005.000,11780.000,11780.000,11775.000,11780.000,11780.000,11900.000,12030.000,23,31,186171.000,137021,大,生猪,2026-06-05,1,,,,,,,,,11900.673,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0";
        var hq_str_nf_SR0="白糖主连,150000,5375.000,5390.000,5316.000,5341.000,5339.000,5340.000,5341.000,5348.000,5403.000,4,45,672582.000,521214,郑,白糖,2026-06-05,1,,,,,,,,,5348.000,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0";
        var hq_str_nf_JD0="鸡蛋主连,150418,4662.000,4728.000,4603.000,4699.000,4699.000,4700.000,4699.000,4658.000,4724.000,25,192,297607.000,547101,大,鸡蛋,2026-06-05,1,,,,,,,,,4658.836,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0";
    """.trimIndent()

    val quotes = StockerQuoteParser.parseQuoteResponse(
        StockerQuoteProvider.SINA, StockerMarketType.Futures, fixture
    )

    check(quotes.size == 3) { "Parser returned ${quotes.size} quotes, expected 3 — regex or branch broken" }

    println("=== Parsed Futures Quotes ===")
    println("code | name      | open    | high    | low     | prevSettle | current | change | pct%  | updateAt")
    println("-----+-----------+---------+---------+---------+------------+---------+--------+-------+----------------------")
    for (q in quotes) {
        println("%-4s | %-9s | %7.3f | %7.3f | %7.3f | %10.3f | %7.3f | %+6.2f | %+5.2f | %s".format(
            q.code, q.name, q.opening, q.high, q.low, q.close, q.current, q.change, q.percentage, q.updateAt
        ))
    }
    println()

    // Sanity invariants that hold for any valid futures quote, regardless of contract specifics:
    for (q in quotes) {
        check(q.code.isNotBlank())                          { "${q.code}: blank code" }
        check(q.name.isNotBlank())                          { "${q.code}: blank name (encoding bug?)" }
        check(q.opening > 0 && q.current > 0)               { "${q.code}: non-positive price (wrong index?)" }
        check(q.high >= q.low)                              { "${q.code}: high < low — index swap" }
        check(q.current in q.low..q.high)                   { "${q.code}: current=${q.current} outside [${q.low},${q.high}] — wrong column" }
        check(q.close > 0)                                  { "${q.code}: non-positive prev settle" }
        check(q.updateAt.matches(Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")))   { "${q.code}: updateAt malformed: ${q.updateAt}" }
        // Allow ±10% intraday move — anything bigger is almost certainly an index mix-up.
        check(kotlin.math.abs(q.percentage) <= 10.0)        { "${q.code}: implausible ${q.percentage}% — index mix-up?" }
    }

    println("OK — invariants hold. Cross-check the printed prices against")
    println("    https://finance.sina.com.cn/futures/quotes/<code>.shtml  (e.g. LH0, SR0, JD0)")
    println("If open/high/low/current don't match the official page, field indices are wrong — fix before commit.")
}
```

- [ ] **Step 5: Run the smoke check**

In IntelliJ, right-click `FuturesParserSmoke.kt` → Run 'FuturesParserSmokeKt'.
Expected: a 3-row table prints; no `IllegalStateException` from any `check`. The runner finishes with
`OK — invariants hold.` and the Sina cross-check URL.

**Cross-check before committing:** open `https://finance.sina.com.cn/futures/quotes/LH0.shtml` (and SR0, JD0) in a browser. The official page's 开/高/低/最新/昨结 must match the printed columns to within rounding. If `current` falls outside `[low, high]`, the field indices are off — shift the textArray indices in the parser branch by 1 and rerun. The most common failure mode is treating 买价 (raw [5]) as 昨结算 (raw [6]).

Once the printed values match the official page for at least one contract, the indices are correct.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteParser.kt src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt
git commit -m "feat(futures): parse Sina nf_ futures response with 昨结算 baseline"
```

---

## Task 5: Wire Futures through `StockerQuoteHttpUtil`

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt`

- [ ] **Step 1: Add a Futures branch to `providerSymbol`**

In `StockerQuoteHttpUtil.kt`, replace the `when` block inside `providerSymbol` (lines 62-68) with:

```kotlin
        return when {
            marketType == StockerMarketType.AShare -> prefixedAShareCode(code)
            marketType == StockerMarketType.HKStocks -> "$prefix${code.uppercase()}"
            marketType == StockerMarketType.Futures -> "$prefix${code.uppercase()}"
            quoteProvider == StockerQuoteProvider.TENCENT && marketType == StockerMarketType.USStocks ->
                "$prefix${code.uppercase()}"
            else -> "$prefix${code.lowercase()}"
        }
```

Futures codes are uppercase by Sina convention (`nf_LH0`, not `nf_lh0`).

- [ ] **Step 2: Skip Futures in `getIntradayData`**

In `getIntradayData`'s `when (marketType)` for `prefixedCode` (lines 116-121), add a `Futures` branch that mirrors `Crypto`:

```kotlin
                val prefixedCode = when (marketType) {
                    StockerMarketType.AShare -> prefixedAShareCode(code)
                    StockerMarketType.HKStocks -> "hk$code"
                    StockerMarketType.USStocks -> "us${code.uppercase()}"
                    StockerMarketType.Crypto -> continue // Crypto not supported for intraday
                    StockerMarketType.Futures -> continue // Futures intraday endpoint differs; out of MVP scope
                }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors remaining only in `StockerActionUtil.kt`, `StockerApp.kt`, `StockerManagementDialog.kt`, `StockerBatchAddDialog.kt`. None in `StockerQuoteHttpUtil.kt`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerQuoteHttpUtil.kt
git commit -m "feat(futures): route Futures codes through Sina with uppercase nf_ prefix"
```

---

## Task 6: Add Futures message-bus topics

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateNotifier.kt`
- Modify: `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteReloadNotifier.kt`
- Modify: `src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteDeleteNotifier.kt`

- [ ] **Step 1: Add `FUTURES_QUOTE_UPDATE_TOPIC`**

In `StockerQuoteUpdateNotifier.kt`, after the `CRYPTO_QUOTE_UPDATE_TOPIC` block (after line 31, before the closing `}`):

```kotlin
        @JvmField
        val FUTURES_QUOTE_UPDATE_TOPIC: Topic<StockerQuoteUpdateNotifier> =
            Topic.create("FuturesQuoteUpdateTopic", StockerQuoteUpdateNotifier::class.java)
```

- [ ] **Step 2: Add `STOCK_FUTURES_QUOTE_RELOAD_TOPIC`**

In `StockerQuoteReloadNotifier.kt`, after the `STOCK_CRYPTO_QUOTE_RELOAD_TOPIC` block (after line 28):

```kotlin
        @JvmField
        val STOCK_FUTURES_QUOTE_RELOAD_TOPIC: Topic<StockerQuoteReloadNotifier> =
            Topic.create("StockerFuturesQuoteReloadTopic", StockerQuoteReloadNotifier::class.java)
```

- [ ] **Step 3: Add `FUTURES_QUOTE_DELETE_TOPIC`**

In `StockerQuoteDeleteNotifier.kt`, after the `CRYPTO_QUOTE_DELETE_TOPIC` block (after line 28):

```kotlin
        @JvmField
        val FUTURES_QUOTE_DELETE_TOPIC: Topic<StockerQuoteDeleteNotifier> =
            Topic.create("FuturesQuoteDeleteTopic", StockerQuoteDeleteNotifier::class.java)
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors only in `StockerActionUtil.kt`, `StockerApp.kt`, `StockerManagementDialog.kt`, `StockerBatchAddDialog.kt`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteUpdateNotifier.kt src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteReloadNotifier.kt src/main/kotlin/com/vermouthx/stocker/listeners/StockerQuoteDeleteNotifier.kt
git commit -m "feat(futures): add Futures update/reload/delete message-bus topics"
```

---

## Task 7: Route Futures through `StockerActionUtil`

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/utils/StockerActionUtil.kt`

- [ ] **Step 1: Add Futures branches in `addStock`**

Replace lines 25 and 31-36 of `StockerActionUtil.kt`. The full new method:

```kotlin
    @JvmStatic
    fun addStock(market: StockerMarketType, suggest: StockerSuggestion, project: Project?): Boolean {
        val setting = StockerSetting.instance
        // Canonicalize A-share codes to "SH000001"/"SZ000001"/"BJ430090". Search-dialog
        // suggestions already arrive prefixed; batch-add hands us raw user input like
        // "000001" that has to be routed to the right exchange before storage.
        val code = if (market == StockerMarketType.AShare) {
            StockerQuoteHttpUtil.canonicalAShareCode(suggest.code)
        } else if (market == StockerMarketType.Futures) {
            // Futures contract codes are short uppercase tickers (LH0, SR0, JD0) — normalize
            // case but otherwise pass through, since Sina's nf_ prefix is added downstream.
            suggest.code.uppercase()
        } else {
            suggest.code
        }
        val fullName = suggest.name
        // Sina-only for Futures; Crypto follows cryptoQuoteProvider; everything else stockProvider.
        val provider = when (market) {
            StockerMarketType.Crypto -> setting.cryptoQuoteProvider
            StockerMarketType.Futures -> StockerQuoteProvider.SINA
            else -> setting.quoteProvider
        }
        if (setting.containsCode(code)) return false
        if (!StockerQuoteHttpUtil.validateCode(market, provider, code)) {
            Messages.showErrorDialog(project, "$fullName is not supported.", "Not Supported Stock")
            return false
        }
        return when (market) {
            StockerMarketType.AShare -> setting.aShareList.add(code)
            StockerMarketType.HKStocks -> setting.hkStocksList.add(code)
            StockerMarketType.USStocks -> setting.usStocksList.add(code)
            StockerMarketType.Crypto -> setting.cryptoList.add(code)
            StockerMarketType.Futures -> setting.futuresList.add(code)
        }
    }
```

Also add the `StockerQuoteProvider` import at the top of the file (after the existing `enums.StockerMarketType` import):

```kotlin
import com.vermouthx.stocker.enums.StockerQuoteProvider
```

- [ ] **Step 2: Add Futures branch in `removeStock`**

Replace the `topic` `when` block in `removeStock` (lines 44-49):

```kotlin
        val topic = when (market) {
            StockerMarketType.AShare -> StockerQuoteDeleteNotifier.STOCK_CN_QUOTE_DELETE_TOPIC
            StockerMarketType.HKStocks -> StockerQuoteDeleteNotifier.STOCK_HK_QUOTE_DELETE_TOPIC
            StockerMarketType.USStocks -> StockerQuoteDeleteNotifier.STOCK_US_QUOTE_DELETE_TOPIC
            StockerMarketType.Crypto -> StockerQuoteDeleteNotifier.CRYPTO_QUOTE_DELETE_TOPIC
            StockerMarketType.Futures -> StockerQuoteDeleteNotifier.FUTURES_QUOTE_DELETE_TOPIC
        }
```

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors only in `StockerApp.kt`, `StockerManagementDialog.kt`, `StockerBatchAddDialog.kt`.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerActionUtil.kt
git commit -m "feat(futures): support Futures in addStock / removeStock (Sina-only)"
```

---

## Task 8: Fetch and publish futures quotes in `StockerApp`

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/StockerApp.kt`

- [ ] **Step 1: Add the futures fetch and publish**

In `StockerApp.kt`, inside `createConsolidatedUpdateThread()`:

After the existing `cryptoQuotes` fetch (line 105), add:

```kotlin
            val futuresQuotes = fetchQuotesIfActive(StockerMarketType.Futures, StockerQuoteProvider.SINA, setting.futuresList) ?: return@Runnable
```

After the existing `cryptoIndices` fetch (line 110), futures has no index list — skip indices entirely. (`StockerMarketIndex` does not get a `Futures` entry; the Futures publisher will pass `emptyList()` for indices.)

After the `cryptoPublisher.syncIndices(cryptoIndices)` line (line 140), add the Futures publisher block:

```kotlin
            val futuresPublisher = messageBus.syncPublisher(FUTURES_QUOTE_UPDATE_TOPIC)
            if (setting.futuresList.isNotEmpty()) {
                futuresPublisher.syncQuotes(futuresQuotes, setting.futuresList.size)
            }
            futuresPublisher.syncIndices(emptyList())
```

In the `allStockQuotes` flatten (line 146), include `futuresQuotes`:

```kotlin
            val allStockQuotes = listOf(aShareQuotes, hkStocksQuotes, usStocksQuotes, cryptoQuotes, futuresQuotes).flatten()
```

- [ ] **Step 2: Add the new topic import**

At the top of `StockerApp.kt`, after the existing `CRYPTO_QUOTE_UPDATE_TOPIC` import (line 14), add:

```kotlin
import com.vermouthx.stocker.listeners.StockerQuoteUpdateNotifier.Companion.FUTURES_QUOTE_UPDATE_TOPIC
```

And after the existing `STOCK_CRYPTO_QUOTE_RELOAD_TOPIC` import (line 11), add:

```kotlin
import com.vermouthx.stocker.listeners.StockerQuoteReloadNotifier.Companion.STOCK_FUTURES_QUOTE_RELOAD_TOPIC
```

- [ ] **Step 3: Clear futures reload topic on `clear()`**

Replace the `clear()` body (lines 64-70) with:

```kotlin
    private fun clear() {
        messageBus.syncPublisher(STOCK_ALL_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_CN_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_HK_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_US_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_CRYPTO_QUOTE_RELOAD_TOPIC).clear()
        messageBus.syncPublisher(STOCK_FUTURES_QUOTE_RELOAD_TOPIC).clear()
    }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors only in `StockerManagementDialog.kt`, `StockerBatchAddDialog.kt`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/StockerApp.kt
git commit -m "feat(futures): fetch and publish futures quotes in consolidated update thread"
```

---

## Task 9: Add a Futures tab to the management dialog

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerManagementDialog.kt`

- [ ] **Step 1: Add the tab to `createCenterPanel`**

In `StockerManagementDialog.kt`, after `tabbedPane.add("Crypto", createTabContent(StockerMarketType.Crypto))` (line 50), add:

```kotlin
        tabbedPane.add("Futures", createTabContent(StockerMarketType.Futures))
```

Update the `tabbedPane.addChangeListener` `when` (lines 53-59):

```kotlin
        tabbedPane.addChangeListener {
            currentMarketSelection = when (tabbedPane.selectedIndex) {
                0 -> StockerMarketType.AShare
                1 -> StockerMarketType.HKStocks
                2 -> StockerMarketType.USStocks
                3 -> StockerMarketType.Crypto
                4 -> StockerMarketType.Futures
                else -> return@addChangeListener
            }
        }
```

After `loadMarketData(StockerMarketType.Crypto, setting.cryptoList)` (line 66), add:

```kotlin
        loadMarketData(StockerMarketType.Futures, setting.futuresList)
```

- [ ] **Step 2: Persist futures on OK**

In `createActions()`, after the `currentSymbols[StockerMarketType.Crypto]?.let { ... }` block (lines 137-139), add:

```kotlin
                        currentSymbols[StockerMarketType.Futures]?.let { symbols ->
                            setting.futuresList = symbols.elements().asSequence().map { it.code }.toMutableList()
                        }
```

- [ ] **Step 3: Update `loadMarketData` provider selection**

In `loadMarketData` (lines 87-93), replace the provider lookup so Futures always uses Sina:

```kotlin
                val provider = when (marketType) {
                    StockerMarketType.Crypto -> setting.cryptoQuoteProvider
                    StockerMarketType.Futures -> com.vermouthx.stocker.enums.StockerQuoteProvider.SINA
                    else -> setting.quoteProvider
                }
```

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: Errors only in `StockerBatchAddDialog.kt`.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerManagementDialog.kt
git commit -m "feat(futures): add Futures tab to manage-favorites dialog"
```

---

## Task 10: Add Futures option to the batch-add dialog

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerBatchAddDialog.kt`

- [ ] **Step 1: Add Futures to the combo box and the `when` mapping**

In `StockerBatchAddDialog.kt`, replace line 25:

```kotlin
    private val marketComboBox = ComboBox(arrayOf("CN (A-Share)", "HK", "US", "Crypto", "Futures (主连)"))
```

Replace the `marketType` `when` (lines 55-61):

```kotlin
        val marketType = when (marketComboBox.selectedIndex) {
            0 -> StockerMarketType.AShare
            1 -> StockerMarketType.HKStocks
            2 -> StockerMarketType.USStocks
            3 -> StockerMarketType.Crypto
            4 -> StockerMarketType.Futures
            else -> StockerMarketType.AShare
        }
```

- [ ] **Step 2: Update the example comment**

Replace the `.comment(...)` line in `createCenterPanel` (line 47):

```kotlin
                    .comment("Enter codes separated by spaces or commas.<br>Examples: 600519 000001 (CN) · LH0 SR0 JD0 (Futures)")
```

- [ ] **Step 3: Full compile**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL. All previous `when` exhaustiveness errors resolved.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerBatchAddDialog.kt
git commit -m "feat(futures): add Futures to batch-add market combo"
```

---

## Task 11: Hardcoded suggest whitelist for futures

**Files:**
- Create: `src/main/kotlin/com/vermouthx/stocker/utils/StockerFuturesWhitelist.kt`

- [ ] **Step 1: Create the whitelist util**

Create `src/main/kotlin/com/vermouthx/stocker/utils/StockerFuturesWhitelist.kt`:

```kotlin
package com.vermouthx.stocker.utils

import com.vermouthx.stocker.entities.StockerSuggestion
import com.vermouthx.stocker.enums.StockerMarketType

/**
 * Hardcoded fallback for the Search Assets dialog when the user is hunting for a domestic
 * commodity-futures continuous contract. Sina's suggest3 endpoint has uneven coverage of
 * `nf_*` symbols, and the contract universe is small (~50 main 主连 codes) so a static map
 * is the lowest-effort MVP. Symbols here must match the codes Sina serves at
 * `https://hq.sinajs.cn/list=nf_<CODE>` — verified for the listed set on 2026-06-05.
 *
 * To add a contract: append `"<UPPER_CODE>" to "<中文名>"` below. Tests on the live endpoint
 * before merging — Sina silently returns an empty quote string for unknown nf_ codes.
 */
object StockerFuturesWhitelist {

    private val contracts: Map<String, String> = linkedMapOf(
        // 农产品 (DCE/CZCE)
        "LH0" to "生猪主连",
        "JD0" to "鸡蛋主连",
        "M0"  to "豆粕主连",
        "RM0" to "菜粕主连",
        "Y0"  to "豆油主连",
        "P0"  to "棕榈油主连",
        "OI0" to "菜籽油主连",
        "A0"  to "豆一主连",
        "B0"  to "豆二主连",
        "C0"  to "玉米主连",
        "CS0" to "玉米淀粉主连",
        "CF0" to "棉花主连",
        "CY0" to "棉纱主连",
        "SR0" to "白糖主连",
        "AP0" to "苹果主连",
        "CJ0" to "红枣主连",
        "PK0" to "花生主连",
        // 化工
        "TA0" to "PTA主连",
        "MA0" to "甲醇主连",
        "EG0" to "乙二醇主连",
        "PP0" to "聚丙烯主连",
        "L0"  to "塑料主连",
        "V0"  to "PVC主连",
        "RU0" to "橡胶主连",
        "BU0" to "沥青主连",
        "FU0" to "燃油主连",
        "SC0" to "原油主连",
        "LU0" to "低硫燃油主连",
        "SP0" to "纸浆主连",
        // 贵金属 + 有色 (SHFE/INE)
        "AU0" to "黄金主连",
        "AG0" to "白银主连",
        "CU0" to "铜主连",
        "AL0" to "铝主连",
        "ZN0" to "锌主连",
        "PB0" to "铅主连",
        "NI0" to "镍主连",
        "SN0" to "锡主连",
        // 黑色
        "RB0" to "螺纹钢主连",
        "HC0" to "热卷主连",
        "I0"  to "铁矿石主连",
        "J0"  to "焦炭主连",
        "JM0" to "焦煤主连",
        "ZC0" to "动力煤主连",
        "FG0" to "玻璃主连",
        "SA0" to "纯碱主连",
        "SF0" to "硅铁主连",
        "SM0" to "锰硅主连"
    )

    /** All known contracts as suggestions, useful when the search box is empty. */
    fun all(): List<StockerSuggestion> = contracts.map { (code, name) ->
        StockerSuggestion(code, name, StockerMarketType.Futures)
    }

    /** Substring (case-insensitive) match on code or Chinese name. Empty query returns all. */
    fun search(query: String): List<StockerSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) return all()
        val needle = q.uppercase()
        return contracts.asSequence()
            .filter { (code, name) -> code.contains(needle) || name.contains(q) }
            .map { (code, name) -> StockerSuggestion(code, name, StockerMarketType.Futures) }
            .toList()
    }
}
```

- [ ] **Step 2: Verify imports compile**

Check `StockerSuggestion` location — should be `com.vermouthx.stocker.entities.StockerSuggestion` with `(code: String, name: String, market: StockerMarketType)` constructor. If the constructor signature differs (e.g. positional differs), adjust the `StockerSuggestion(...)` calls to match. Quick check:

Run: `grep -n "class StockerSuggestion\|data class StockerSuggestion" src/main/kotlin/com/vermouthx/stocker/entities/`
Confirm constructor order before continuing.

- [ ] **Step 3: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/utils/StockerFuturesWhitelist.kt
git commit -m "feat(futures): add ~46-contract suggest whitelist for 主连 search"
```

---

## Task 12: Add Futures search mode to suggestion dialog

**Files:**
- Modify: `src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerSuggestionDialog.kt`

- [ ] **Step 1: Add `FUTURES` to the `SearchMode` enum**

In `StockerSuggestionDialog.kt`, replace the `SearchMode` enum (lines 47-50):

```kotlin
    private enum class SearchMode(val displayName: String) {
        STOCKS("Stocks (CN/HK/US)"),
        CRYPTO("Crypto"),
        FUTURES("Futures (主连)")
    }
```

- [ ] **Step 2: Wire the mode combo box**

Replace the `modeComboBox` creation (lines 113-114):

```kotlin
        val modeComboBox = ComboBox(arrayOf(
            SearchMode.STOCKS.displayName,
            SearchMode.CRYPTO.displayName,
            SearchMode.FUTURES.displayName
        ))
```

Replace the combo box's `addActionListener` body (lines 115-126) — both the `when` and the trailing trigger so switching to FUTURES with no text typed surfaces the whole list:

```kotlin
        modeComboBox.addActionListener {
            searchMode = when (modeComboBox.selectedIndex) {
                0 -> SearchMode.STOCKS
                1 -> SearchMode.CRYPTO
                2 -> SearchMode.FUTURES
                else -> SearchMode.STOCKS
            }
            // Always re-search on mode change; the empty-query gate inside performSearch
            // decides whether to show the placeholder (STOCKS/CRYPTO) or the full whitelist (FUTURES).
            performSearch(searchTextField.text.trim())
        }
```

- [ ] **Step 3: Branch the search itself**

Inside `performSearch`'s scheduled body (lines 78-105), replace the entire body of the `service.schedule({...})` block with a Futures branch added:

```kotlin
                searchTask = service.schedule({
                    try {
                        val filteredSuggestions = when (searchMode) {
                            SearchMode.FUTURES -> StockerFuturesWhitelist.search(text)
                            SearchMode.CRYPTO -> StockerSuggestHttpUtil.suggest(
                                text, setting.cryptoQuoteProvider, setOf(StockerMarketType.Crypto)
                            )
                            SearchMode.STOCKS -> StockerSuggestHttpUtil.suggest(
                                text, setting.quoteProvider, setOf(
                                    StockerMarketType.AShare,
                                    StockerMarketType.HKStocks,
                                    StockerMarketType.USStocks
                                )
                            )
                        }

                        SwingUtilities.invokeLater {
                            isLoading = false
                            suggestions = filteredSuggestions
                            refreshScrollPane(scrollPane)
                        }
                    } catch (e: Exception) {
                        log.warn("Failed to fetch suggestions", e)
                        SwingUtilities.invokeLater {
                            isLoading = false
                            refreshScrollPane(scrollPane)
                        }
                    }
                }, 300, TimeUnit.MILLISECONDS)
```

For Futures, also allow an empty-query show-all (since the whitelist is small). Replace the `if (text.isEmpty()) { ... } else { ... }` block (lines 67-107) — specifically the `if` branch (line 67-71) — with:

```kotlin
            if (text.isEmpty() && searchMode != SearchMode.FUTURES) {
                isLoading = false
                suggestions = emptyList()
                SwingUtilities.invokeLater { refreshScrollPane(scrollPane) }
            } else {
```

And in the Futures branch above, allow the empty-query case to call `StockerFuturesWhitelist.search("")` which returns `all()`.

- [ ] **Step 4: Update the empty-state message**

Replace the `searchMode` `when` inside `refreshScrollPane` (lines 179-182):

```kotlin
            val message = when (searchMode) {
                SearchMode.STOCKS -> "Type to search for stocks (CN/HK/US)..."
                SearchMode.CRYPTO -> "Type to search for crypto..."
                SearchMode.FUTURES -> "No futures contracts matched. Try LH0, SR0, JD0..."
            }
```

- [ ] **Step 5: Add the import**

At the top of `StockerSuggestionDialog.kt`, add:

```kotlin
import com.vermouthx.stocker.utils.StockerFuturesWhitelist
```

- [ ] **Step 6: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/vermouthx/stocker/views/dialogs/StockerSuggestionDialog.kt
git commit -m "feat(futures): add Futures search mode to suggestion dialog"
```

---

## Task 13: i18n + version bump + CHANGELOG

**Files:**
- Modify: `src/main/resources/messages/StockerBundle.properties`
- Modify: `src/main/resources/messages/StockerBundle_zh_CN.properties`
- Modify: `gradle.properties`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Add i18n keys (English)**

Append to `src/main/resources/messages/StockerBundle.properties` (under the `# Tabs` section, after `tab.watchlist=Watchlist`):

```
tab.futures=Futures
market.futures=Futures (主连)
```

The new keys are optional for MVP (the dialog labels are hard-coded English now); add them so future code can switch to `StockerBundle.message(...)` lookups without another rev.

- [ ] **Step 2: Add i18n keys (Chinese)**

Append the same two keys in `StockerBundle_zh_CN.properties` with Chinese values:

```
tab.futures=期货
market.futures=期货（主连）
```

- [ ] **Step 3: Bump plugin version**

In `gradle.properties`, replace `pluginVersion=1.25.0` with:

```
pluginVersion=1.26.0
```

- [ ] **Step 4: Update CHANGELOG**

Insert a new section at the top of `CHANGELOG.md` (under the existing `# Changelog` heading, above the previous version):

```markdown
## [1.26.0] - 2026-06-05

### Added
- Domestic commodity futures (主连) as a fifth market type — Sina data only.
- ~46-contract suggest whitelist covering 农产品/化工/金属/黑色 main 主连 codes (LH0, SR0, JD0, etc.).
- "Futures" tab in Manage Favorites dialog; "Futures (主连)" option in Batch Add and Search dialogs.

### Notes
- Change % for futures is computed against 昨结算 (prev settle), per CFFEX/DCE convention.
- Sparkline (intraday minute trend) is not yet wired for futures — fallback to flat.
- Tencent provider does not support futures; the option is hidden / falls back to Sina.
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/messages/StockerBundle.properties src/main/resources/messages/StockerBundle_zh_CN.properties gradle.properties CHANGELOG.md
git commit -m "chore(futures): bump to 1.26.0 with futures support changelog"
```

---

## Task 14: Sandbox smoke test + cleanup

**Files:**
- Delete: `src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt`

- [ ] **Step 1: Launch the IDE sandbox**

Run: `./gradlew runIde`
Expected: a sandboxed IDE starts within ~30s with Stocker plugin loaded.

- [ ] **Step 2: Add a futures code via Batch Add**

In the sandbox:
1. Open the Stocker tool window.
2. Click "Batch Add", select "Futures (主连)", enter `LH0 SR0 JD0`, OK.
3. Expected: the Favorites table grows three rows after one refresh cycle (≤5s default `refreshInterval`). Each row shows 中文名 (e.g. `生猪主连`), a non-zero current price, and a change% calculated vs 昨结算.

- [ ] **Step 3: Manage / reorder / delete**

1. Open Manage Favorites → "Futures" tab. The three rows are listed.
2. Use "Move to Top" / "Move to Bottom" on one row — confirm reorder persists after OK and re-open.
3. Delete one row via the toolbar minus button → OK. Confirm it disappears from the Favorites table on next refresh.

- [ ] **Step 4: Search dialog**

1. Open Search Assets, switch the "Search for:" combo to "Futures (主连)".
2. With no text typed: confirm the full ~46-entry list renders.
3. Type `糖`: confirm `SR0 白糖主连` appears.
4. Type `LH`: confirm `LH0 生猪主连` appears.
5. Click the Add button on one: confirm it lands in the Favorites table after refresh.

- [ ] **Step 5: Persistence across restart**

Close the sandbox IDE, relaunch via `./gradlew runIde`, confirm futures codes are still present in Favorites and Manage dialog.

- [ ] **Step 6: Delete the scratch smoke file**

Run: `rm src/main/kotlin/com/vermouthx/stocker/scratch/FuturesParserSmoke.kt`
If the `scratch/` directory is now empty: `rmdir src/main/kotlin/com/vermouthx/stocker/scratch`

- [ ] **Step 7: Final build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit cleanup**

```bash
git add -u src/main/kotlin/com/vermouthx/stocker/scratch
git commit -m "chore(futures): remove parser smoke runner used during development"
```

---

## Out of scope (explicitly deferred)

- 股指期货 (`nf_IF0`, `IC0`, `IH0`, `IM0`) — share `nf_` prefix and parsing, but the user requested commodity 主连 only. Adding them after MVP is a one-line per-contract whitelist addition.
- 国债期货 (`nf_T0`, `TF0`, `TS0`) — same as above.
- 外盘期货 (`hf_CL`, `hf_GC`) — different prefix and Sina field layout; needs a second `Foreign` branch or a sub-enum. Defer until users ask.
- Intraday minute sparkline for futures — Sina's `web.ifzq.gtimg.cn` minute endpoint accepts equities only; futures intraday would need `https://stock2.finance.sina.com.cn/futures/api/json.php/IndexService.getInnerFuturesMiniKLine5m`. Out of MVP.
- Tencent provider for futures — single-provider MVP. Tencent has `qt.gtimg.cn/q=nf_LH0` but field layout differs and we lose nothing by skipping it for V1.
- Contract unit / multiplier display (元/手, 元/吨) — current `StockerQuote` has no unit field; would require a column extension.
- Futures-specific health badge / entry-timing integration — finance/ bridge isn't aware of futures symbols and shouldn't be reworked for MVP.
