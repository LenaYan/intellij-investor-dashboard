package com.vermouthx.stocker.utils

import com.vermouthx.stocker.enums.StockerMarketType
import com.vermouthx.stocker.enums.StockerQuoteProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fixtures are real responses captured from the live endpoints on 2026-06-11.
 * They pin the field-index assumptions the parsers encode; if a provider
 * shuffles its format these tests fail before users see broken tables.
 */
class StockerQuoteParserTest {

    private val sinaAShareResponse = """
        var hq_str_sh600519="贵州茅台,1272.120,1275.880,1270.320,1282.880,1266.910,1270.340,1271.670,1365304,1737251671.000,100,1270.340,100,1270.330,100,1270.210,500,1270.200,200,1270.180,600,1271.670,100,1271.770,100,1271.780,800,1271.790,100,1271.800,2026-06-11,11:22:37,00,";
        var hq_str_sz000001="平安银行,11.320,11.320,11.300,11.390,11.250,11.290,11.300,65961984,746670424.350,448200,11.290,651100,11.280,775800,11.270,1057200,11.260,1347100,11.250,158500,11.300,462000,11.310,527000,11.320,1146800,11.330,1101601,11.340,2026-06-11,11:22:39,00";
        var hq_str_bj920371="欧福蛋业,8.980,8.880,9.030,9.380,8.430,9.020,9.030,4737857,42294004.100,33,9.020,58,9.010,1500,9.000,2000,8.970,6600,8.960,1152,9.030,4000,9.040,3000,9.080,2080,9.090,2000,9.100,2026-06-11,11:22:37,00,21.0776,0.0000,0,8300000,B,T";
        var hq_str_nonexist="";
    """.trimIndent()

    @Test
    fun `sina a-share response parses with canonical prefixed codes`() {
        val quotes = StockerQuoteParser.parseQuoteResponse(
            StockerQuoteProvider.SINA, StockerMarketType.AShare, sinaAShareResponse
        )
        assertEquals(3, quotes.size, "empty hq_str entries must be skipped")
        assertEquals(listOf("SH600519", "SZ000001", "BJ920371"), quotes.map { it.code })

        val moutai = quotes[0]
        assertEquals("贵州茅台", moutai.name)
        assertEquals(1270.32, moutai.current)
        assertEquals(1272.12, moutai.opening)
        assertEquals(1275.88, moutai.close)
        assertEquals(1282.88, moutai.high)
        assertEquals(1266.91, moutai.low)
        assertEquals(-5.56, moutai.change)
        assertEquals(-0.44, moutai.percentage)
        assertEquals("2026-06-11 11:22:37", moutai.updateAt)
    }

    @Test
    fun `sina futures response uses prev settle and trailing date field`() {
        val response = """
            var hq_str_nf_LH0="生猪连续,112248,11970.000,12005.000,11800.000,0.000,11820.000,11825.000,11825.000,0.000,11970.000,86,34,188909.000,87515,连,生猪,2026-06-11,1,,,,,,,,,11902.801,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0,0.000,0";
        """.trimIndent()
        val quotes = StockerQuoteParser.parseQuoteResponse(
            StockerQuoteProvider.SINA, StockerMarketType.Futures, response
        )
        assertEquals(1, quotes.size)
        val lh = quotes[0]
        assertEquals("LH0", lh.code)
        assertEquals(11825.0, lh.current)
        assertEquals(11820.0, lh.close, "change base must be 昨结算 (prev settle), not prev close")
        assertEquals(5.0, lh.change)
        assertEquals(0.04, lh.percentage)
        assertEquals("2026-06-11 11:22:48", lh.updateAt)
    }

    @Test
    fun `sina futures truncated line is dropped instead of throwing`() {
        val truncated = """var hq_str_nf_XX0="名称,112248,1.0,2.0,3.0,4.0,5.0,6.0,7.0,8.0";"""
        val quotes = StockerQuoteParser.parseQuoteResponse(
            StockerQuoteProvider.SINA, StockerMarketType.Futures, truncated
        )
        assertTrue(quotes.isEmpty())
    }

    @Test
    fun `tencent a-share response parses price and percentage fields`() {
        val response =
            "v_sh600519=\"1~贵州茅台~600519~1271.00~1275.88~1272.12~13664~6819~6834~1270.21~2~1270.20~3~1270.18~1~1270.05~1~1270.03~2~1271.60~1~1271.61~2~1271.62~2~1271.63~2~1271.64~1~~20260611112246~-4.88~-0.38~1282.88~1266.91~1271.00/13664/1738649388~13664~173865~0.11~19.21~~1282.88~1266.91~1.25~15888.54~15888.54~5.93~1403.47~1148.29~0.89~1~1272.43~14.58~19.30~~~0.33~173864.9388~0.0000~0~ ~GP-A~-7.71~0.24~4.07~30.53~26.78~1568.00~1250.10~-0.39~-5.30~-10.09~1250081601~1250081601~5.88~-13.96~1250081601~~~-11.02~-0.07~~CNY~0~___D__F__N~1270.00~14~\";"
        val quotes = StockerQuoteParser.parseQuoteResponse(
            StockerQuoteProvider.TENCENT, StockerMarketType.AShare, response
        )
        assertEquals(1, quotes.size)
        val moutai = quotes[0]
        assertEquals("SH600519", moutai.code)
        assertEquals("贵州茅台", moutai.name)
        assertEquals(1271.0, moutai.current)
        assertEquals(1275.88, moutai.close)
        assertEquals(1272.12, moutai.opening)
        assertEquals(1282.88, moutai.high)
        assertEquals(1266.91, moutai.low)
        assertEquals(-4.88, moutai.change)
        assertEquals(-0.38, moutai.percentage)
        assertEquals("2026-06-11 11:22:46", moutai.updateAt)
    }

    @Test
    fun `tencent none-match lines are skipped`() {
        val quotes = StockerQuoteParser.parseQuoteResponse(
            StockerQuoteProvider.TENCENT, StockerMarketType.AShare, "v_pv_none_match=\"1\";"
        )
        assertTrue(quotes.isEmpty())
    }

    @Test
    fun `garbage input never throws`() {
        for (market in StockerMarketType.entries) {
            for (provider in StockerQuoteProvider.entries) {
                StockerQuoteParser.parseQuoteResponse(provider, market, "")
                StockerQuoteParser.parseQuoteResponse(provider, market, "not a quote")
                StockerQuoteParser.parseQuoteResponse(provider, market, "var hq_str_x=\"a,b\";")
            }
        }
    }
}
