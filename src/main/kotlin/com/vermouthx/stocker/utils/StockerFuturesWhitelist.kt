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
