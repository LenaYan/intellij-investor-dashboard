package com.vermouthx.stocker.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StockerAShareCodeTest {

    @Test
    fun `exchange prefix heuristics`() {
        assertEquals("sh600519", StockerQuoteHttpUtil.prefixedAShareCode("600519"))
        assertEquals("sh688331", StockerQuoteHttpUtil.prefixedAShareCode("688331"))
        assertEquals("sh512690", StockerQuoteHttpUtil.prefixedAShareCode("512690"), "ETF 5xxxxx is Shanghai")
        assertEquals("sz000001", StockerQuoteHttpUtil.prefixedAShareCode("000001"))
        assertEquals("sz300558", StockerQuoteHttpUtil.prefixedAShareCode("300558"))
        assertEquals("bj920371", StockerQuoteHttpUtil.prefixedAShareCode("920371"), "92xxxx is Beijing")
        assertEquals("bj430090", StockerQuoteHttpUtil.prefixedAShareCode("430090"))
        assertEquals("sh900901", StockerQuoteHttpUtil.prefixedAShareCode("900901"), "9 not 92/93 is Shanghai B")
    }

    @Test
    fun `existing prefixes pass through lowercased`() {
        assertEquals("sh600519", StockerQuoteHttpUtil.prefixedAShareCode("SH600519"))
        assertEquals("bj920371", StockerQuoteHttpUtil.prefixedAShareCode("bj920371"))
    }

    @Test
    fun `canonical form is uppercase prefixed`() {
        assertEquals("SH600519", StockerQuoteHttpUtil.canonicalAShareCode("600519"))
        assertEquals("SZ000001", StockerQuoteHttpUtil.canonicalAShareCode("sz000001"))
    }
}
