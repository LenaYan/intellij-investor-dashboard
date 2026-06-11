package com.vermouthx.stocker.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StockerIntradayParserTest {

    /** Tencent minute-data response in the shape documented on the fetcher. */
    private fun response(qtClose: String?): String {
        val qt = if (qtClose != null) {
            ""","qt":{"v_ff_sh600519":[],"sh600519":["1","贵州茅台","600519","1271.00","$qtClose","1268.00"]}"""
        } else {
            ""
        }
        return """min_data_600519={"code":0,"msg":"","data":{"sh600519":{"data":{"date":"20260611","data":["0930 1270.00 100 127000","0931 1271.50 80 101720","0932 1269.80 60 76188"]}$qt}}}"""
    }

    @Test
    fun `parses minute prices in order`() {
        val data = StockerIntradayParser.parse("600519", response("1275.88"))!!
        assertEquals(listOf(1270.0, 1271.5, 1269.8), data.prices)
        assertEquals("600519", data.code)
    }

    @Test
    fun `reads previous close from the qt array`() {
        val data = StockerIntradayParser.parse("600519", response("1275.88"))!!
        assertEquals(1275.88, data.close)
    }

    @Test
    fun `falls back to first price when qt is absent`() {
        val data = StockerIntradayParser.parse("600519", response(null))!!
        assertEquals(1270.0, data.close)
    }

    @Test
    fun `returns null for malformed or empty responses`() {
        assertNull(StockerIntradayParser.parse("600519", "not json at all"))
        assertNull(StockerIntradayParser.parse("600519", """min_data_600519={"code":0,"data":{}}"""))
        assertNull(
            StockerIntradayParser.parse(
                "600519",
                """min_data_600519={"code":0,"data":{"sh600519":{"data":{"data":[]}}}}""",
            ),
        )
    }

    @Test
    fun `skips malformed minute entries but keeps valid ones`() {
        val text =
            """min_data_600519={"code":0,"data":{"sh600519":{"data":{"data":["0930 1270.00 100 127000","garbage","0932 1269.80 60 76188"]}}}}"""
        val data = StockerIntradayParser.parse("600519", text)!!
        assertEquals(listOf(1270.0, 1269.8), data.prices)
    }
}
