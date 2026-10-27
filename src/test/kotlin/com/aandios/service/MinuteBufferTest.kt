/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinuteBufferTest {

    private val trade = Trade(
        exchange = "Binance",
        symbol = "BTCUSDT",
        timestamp = 1_000L,
        price = BigDecimal("50000"),
        quantity = BigDecimal("1"),
        isBuy = true
    )

    @Test
    fun `trades are returned for their minute and cleared afterwards`() {
        val buffer = MinuteBuffer()
        buffer.addTrade(trade)

        val trades = buffer.getTrades("btcusdt", 0L, 60_000L)
        assertEquals(1, trades.size)
        assertEquals(1_000L, trades.first().timestamp)

        buffer.clearTrades("btcusdt", 0L)
        assertTrue(buffer.getTrades("btcusdt", 0L, 60_000L).isEmpty())
    }

    @Test
    fun `trades from other minutes are not returned`() {
        val buffer = MinuteBuffer()
        buffer.addTrade(trade)
        buffer.addTrade(trade.copy(timestamp = 61_000L))

        assertEquals(1, buffer.getTrades("BTCUSDT", 0L, 60_000L).size)
        assertEquals(1, buffer.getTrades("BTCUSDT", 60_000L, 120_000L).size)
    }
}
