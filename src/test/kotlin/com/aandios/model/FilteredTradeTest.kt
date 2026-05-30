package com.aandios.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilteredTradeTest {

    @Test
    fun `TradeCategory name used for DB serialization`() {
        assertEquals("LARGE", TradeCategory.LARGE.name)
        assertEquals("VERY_LARGE", TradeCategory.VERY_LARGE.name)
        assertEquals("WHALE", TradeCategory.WHALE.name)
    }

    @Test
    fun `FilteredTrade with category`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, 50000.0, 10.0, true)
        val ft = FilteredTrade(
            trade = trade,
            volumeUsd = BigDecimal("500000"),
            percentileThreshold = 0.98,
            volumeThreshold = BigDecimal("100000"),
            tradeCategory = TradeCategory.WHALE,
            windowStartTime = 0L,
            windowEndTime = 1000L,
            windowTotalTrades = 50000
        )
        assertEquals(trade, ft.trade)
        assertEquals(TradeCategory.WHALE, ft.tradeCategory)
        assertEquals(BigDecimal("500000"), ft.volumeUsd)
    }

    @Test
    fun `FilteredTrade without category`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, 50000.0, 1.0, true)
        val ft = FilteredTrade(
            trade = trade,
            volumeUsd = BigDecimal("50000"),
            percentileThreshold = 0.98,
            volumeThreshold = BigDecimal("100000"),
            tradeCategory = null,
            windowStartTime = 0L,
            windowEndTime = 1000L,
            windowTotalTrades = 50000
        )
        assertNull(ft.tradeCategory)
    }
}
