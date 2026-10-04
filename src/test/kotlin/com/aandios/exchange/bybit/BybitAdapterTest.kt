/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.exchange.bybit

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class BybitAdapterTest {

    private val adapter = BybitAdapter()

    @Test
    fun `parseTrade valid JSON with Buy side`() {
        val json = """{"topic":"publicTrade.BTCUSDT","type":"snapshot","data":[{"T":1704067200000,"p":"50000.00","v":"1.5","S":"Buy"}]}"""
        val trade = adapter.parseTrade(json, "BTCUSDT")!!
        assertEquals("Bybit", trade.exchange)
        assertEquals("BTCUSDT", trade.symbol)
        assertEquals(1704067200000L, trade.timestamp)
        assertEquals(BigDecimal("50000.00"), trade.price)
        assertEquals(BigDecimal("1.5"), trade.quantity)
        assertTrue(trade.isBuy)
    }

    @Test
    fun `parseTrade Sell side`() {
        val json = """{"topic":"publicTrade.BTCUSDT","data":[{"T":1704067200000,"p":"50000.00","v":"1.5","S":"Sell"}]}"""
        val trade = adapter.parseTrade(json, "BTCUSDT")!!
        assertFalse(trade.isBuy)
    }

    @Test
    fun `parseTrade wrong topic returns null`() {
        val json = """{"topic":"publicTrade.ETHUSDT","data":[{"T":1704067200000,"p":"50000.00","v":"1.5","S":"Buy"}]}"""
        assertNull(adapter.parseTrade(json, "BTCUSDT"))
    }

    @Test
    fun `parseTrade empty data array returns null`() {
        val json = """{"topic":"publicTrade.BTCUSDT","data":[]}"""
        assertNull(adapter.parseTrade(json, "BTCUSDT"))
    }

    @Test
    fun `parseTrade malformed JSON returns null`() {
        assertNull(adapter.parseTrade("not json", "BTCUSDT"))
        assertNull(adapter.parseTrade("{}", "BTCUSDT"))
    }

    @Test
    fun `parseTrade missing fields returns null`() {
        val json = """{"topic":"publicTrade.BTCUSDT","data":[{"T":1704067200000}]}"""
        assertNull(adapter.parseTrade(json, "BTCUSDT"))
    }

    @Test
    fun `isTradeMessage identifies publicTrade topic`() {
        assertTrue(adapter.isTradeMessage("""{"topic":"publicTrade.BTCUSDT","data":[]}"""))
    }

    @Test
    fun `isTradeMessage rejects non-trade messages`() {
        assertFalse(adapter.isTradeMessage("""{"topic":"orderbook.BTCUSDT"}"""))
        assertFalse(adapter.isTradeMessage("""{"other":"data"}"""))
        assertFalse(adapter.isTradeMessage("not json"))
    }

    @Test
    fun `getWebSocketUrl returns spot URL`() {
        assertEquals("wss://stream.bybit.com/v5/public/spot", adapter.getWebSocketUrl("BTCUSDT"))
    }
}
