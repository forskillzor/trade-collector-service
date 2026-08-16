/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.ZoneOffset
import kotlin.test.assertEquals

class TradeTest {

    @Test
    fun `getVolumeUsd returns price times quantity`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("2"), true)
        assertEquals(BigDecimal("100000"), trade.getVolumeUsd())
    }

    @Test
    fun `getVolumeUsd with zero quantity`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("0"), true)
        assertEquals(BigDecimal("0"), trade.getVolumeUsd())
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
        assertEquals(BigDecimal("3500.12345678"), trade.price)
        assertEquals(BigDecimal("1.5"), trade.quantity)
        assertEquals(true, trade.isBuy)
    }

    @Test
    fun `toLocalDateTime converts to UTC`() {
        val trade = Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("50000"), BigDecimal("1"), true)
        val ldt = trade.toLocalDateTime()
        assertEquals(ZoneOffset.UTC, ldt.atZone(ZoneOffset.UTC).offset)
        assertEquals(2024, ldt.year)
        assertEquals(1, ldt.monthValue)
        assertEquals(1, ldt.dayOfMonth)
    }

    @Test
    fun `isBuy false for sell trade`() {
        val trade = Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), false)
        assertEquals(false, trade.isBuy)
        assertEquals(BigDecimal("50000"), trade.getVolumeUsd())
    }
}
