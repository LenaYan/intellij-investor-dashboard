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
