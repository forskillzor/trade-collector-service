/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.exchange.binance

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class BinanceAdapterTest {

    private val adapter = BinanceAdapter()

    @Test
    fun `parseTrade valid JSON returns Trade with inverted isBuy`() {
        val json = """{"e":"aggTrade","E":1704067200000,"s":"BTCUSDT","t":12345,"p":"50000.00","q":"1.5","T":1704067200000,"m":true,"M":true}"""
        val trade = adapter.parseTrade(json, "btcusdt")!!
        assertEquals("Binance", trade.exchange)
        assertEquals("BTCUSDT", trade.symbol)
        assertEquals(1704067200000L, trade.timestamp)
        assertEquals(BigDecimal("50000.00"), trade.price)
        assertEquals(BigDecimal("1.5"), trade.quantity)
        assertFalse(trade.isBuy) // m=true → isBuy=false
    }

    @Test
    fun `parseTrade m=false means isBuy=true`() {
        val json = """{"e":"aggTrade","p":"45000.00","q":"2.0","T":1704067200000,"m":false}"""
        val trade = adapter.parseTrade(json, "btcusdt")!!
        assertTrue(trade.isBuy)
    }

    @Test
    fun `parseTrade malformed JSON returns null`() {
        assertNull(adapter.parseTrade("not json", "btcusdt"))
        assertNull(adapter.parseTrade("{}", "btcusdt"))
        assertNull(adapter.parseTrade("", "btcusdt"))
    }

    @Test
    fun `parseTrade missing field returns null`() {
        val json = """{"e":"aggTrade","p":"50000.00"}"""
        assertNull(adapter.parseTrade(json, "btcusdt"))
    }

    @Test
    fun `isTradeMessage identifies aggTrade event`() {
        assertTrue(adapter.isTradeMessage("""{"e":"aggTrade","other":"data"}"""))
    }

    @Test
    fun `isTradeMessage rejects non-trade events`() {
        assertFalse(adapter.isTradeMessage("""{"e":"depthUpdate"}"""))
        assertFalse(adapter.isTradeMessage("""{"other":"data"}"""))
        assertFalse(adapter.isTradeMessage("not json"))
    }
}
