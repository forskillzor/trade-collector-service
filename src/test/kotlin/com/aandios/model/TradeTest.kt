package com.aandios.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.test.assertEquals

class TradeTest {

    @Test
    fun `getVolumeUsd returns price times quantity`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, 50000.0, 2.0, true)
        assertEquals(100000.0, trade.getVolumeUsd())
    }

    @Test
    fun `getVolumeUsd with zero quantity`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, 50000.0, 0.0, true)
        assertEquals(0.0, trade.getVolumeUsd())
    }

    @Test
    fun `fromRaw converts BigDecimal to Double correctly`() {
        val trade = Trade.fromRaw(
            "Binance", "ETHUSDT", 1000L,
            BigDecimal("3500.12345678"),
            BigDecimal("1.5"),
            isBuy = true
        )
        assertEquals("Binance", trade.exchange)
        assertEquals("ETHUSDT", trade.symbol)
        assertEquals(1000L, trade.timestamp)
        assertEquals(3500.12345678, trade.price)
        assertEquals(1.5, trade.quantity)
        assertEquals(true, trade.isBuy)
    }

    @Test
    fun `toLocalDateTime converts to UTC`() {
        val trade = Trade("Binance", "BTCUSDT", 1704067200000L, 50000.0, 1.0, true)
        val ldt = trade.toLocalDateTime()
        assertEquals(ZoneOffset.UTC, ldt.atZone(ZoneOffset.UTC).offset)
        assertEquals(2024, ldt.year)
        assertEquals(1, ldt.monthValue)
        assertEquals(1, ldt.dayOfMonth)
    }

    @Test
    fun `isBuy false for sell trade`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, 50000.0, 1.0, false)
        assertEquals(false, trade.isBuy)
        assertEquals(50000.0, trade.getVolumeUsd())
    }
}
