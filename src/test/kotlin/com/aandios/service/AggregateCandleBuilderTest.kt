/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class AggregateCandleBuilderTest {

    private val dao = mockk<TradeDAO>(relaxed = true)
    private val processor = AggregateProcessor(dao, listOf("1m"))

    private fun createBuilder(
        exchange: String = "Binance",
        symbol: String = "BTCUSDT",
        timeframe: String = "1m",
        startTime: Long = 1704067200000L
    ) = processor.AggregateCandleBuilder(exchange, symbol, timeframe, startTime)

    @Test
    fun `addTrade tracks min and max price`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("50000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("51000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200002L, BigDecimal("49000"), BigDecimal("1"), true))

        assertEquals(BigDecimal("49000"), builder.minPrice)
        assertEquals(BigDecimal("51000"), builder.maxPrice)
        assertEquals(3L, builder.totalTicks)
    }

    @Test
    fun `addTrade separates bid and ask volumes`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("50000"), BigDecimal("2"), true))  // bid
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("50000"), BigDecimal("1"), false)) // ask
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200002L, BigDecimal("50000"), BigDecimal("3"), true))  // bid

        val level = builder.priceLevels[BigDecimal("50000")]!!
        assertEquals(BigDecimal("5"), level.bidVolume) // 2.0 + 3.0
        assertEquals(BigDecimal("1"), level.askVolume) // 1.0
        assertEquals(2, level.bidCount)
        assertEquals(1, level.askCount)
    }

    @Test
    fun `bid and ask counts are independent`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("50000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("50000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200002L, BigDecimal("50000"), BigDecimal("1"), false))

        val level = builder.priceLevels[BigDecimal("50000")]!!
        assertEquals(2, level.bidCount)
        assertEquals(1, level.askCount)
    }

    @Test
    fun `buildPriceLevelsJson produces valid JSON`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("49000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("50000"), BigDecimal("2"), false))

        val json = builder.buildPriceLevelsJson()
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertTrue(json.contains("49000"))
        assertTrue(json.contains("50000"))
    }

    @Test
    fun `buildPriceLevelsJson sorts by price ascending`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("51000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("49000"), BigDecimal("1"), true))

        val json = builder.buildPriceLevelsJson()
        val pos49000 = json.indexOf("49000")
        val pos51000 = json.indexOf("51000")
        assertTrue(pos49000 < pos51000, "49000 should appear before 51000 in sorted JSON: $json")
    }

    @Test
    fun `buildAggregate without trades has zero values`() {
        val builder = createBuilder()
        val aggregate = builder.buildAggregate()

        assertEquals(0L, aggregate.totalTicks)
        assertEquals(BigDecimal.ZERO, aggregate.minPrice)
        assertEquals(BigDecimal.ZERO, aggregate.maxPrice)
        assertEquals(0, aggregate.priceLevels)
        assertEquals("[]", aggregate.priceLevelsJson)
    }

    @Test
    fun `buildAggregate includes correct priceLevels count`() {
        val builder = createBuilder()
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200000L, BigDecimal("50000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200001L, BigDecimal("51000"), BigDecimal("1"), true))
        builder.addTrade(Trade("Binance", "BTCUSDT", 1704067200002L, BigDecimal("50000"), BigDecimal("1"), false))

        val aggregate = builder.buildAggregate()
        assertEquals(2, aggregate.priceLevels) // two unique prices: 50000, 51000
        assertEquals("1m", aggregate.timeframe)
        assertEquals("Binance", aggregate.exchange)
        assertEquals("BTCUSDT", aggregate.symbol)
    }

    @Test
    fun `endTime calculated correctly for 1m`() {
        val builder = createBuilder(startTime = 1704067200000L)
        assertEquals(1704067260000L, builder.endTime)
    }

    @Test
    fun `minPrice stays null until first trade`() {
        val builder = createBuilder()
        assertNull(builder.minPrice)
        assertNull(builder.maxPrice)
    }
}
