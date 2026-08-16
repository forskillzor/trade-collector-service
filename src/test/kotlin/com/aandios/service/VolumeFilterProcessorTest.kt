/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolumeFilterProcessorTest {

    private val dao = mockk<TradeDAO>(relaxed = true)
    private val windowSize = 5
    private val slideStep = 2
    private val filterPercentile = 0.98

    @AfterEach
    fun cleanup() {
        clearMocks(dao)
    }

    private fun createTrade(
        exchange: String = "Binance",
        symbol: String = "BTCUSDT",
        timestamp: Long = 1000L,
        price: BigDecimal = BigDecimal("50000"),
        quantity: BigDecimal = BigDecimal("1"),
        isBuy: Boolean = true
    ) = Trade(exchange, symbol, timestamp, price, quantity, isBuy)

    @Test
    fun `processTrade adds to sliding window`() {
        val processor = VolumeFilterProcessor(dao, windowSize, slideStep, filterPercentile)

        processor.processTrade(createTrade(quantity = BigDecimal("2")))  // vol = 100000
        processor.processTrade(createTrade(quantity = BigDecimal("3")))  // vol = 150000

        val stats = processor.getStats()
        val window = stats["Binance_BTCUSDT"] as Map<*, *>
        assertEquals(2, window["totalTrades"])
        assertEquals(2, window["windowSize"])
    }

    @Test
    fun `statistics computed on slideStep interval`() {
        val processor = VolumeFilterProcessor(dao, windowSize = 10, slideStep = 3, filterPercentile)

        // Feed 3 trades to trigger first recalculation
        processor.processTrade(createTrade(timestamp = 1, price = BigDecimal("10000"), quantity = BigDecimal("1")))
        processor.processTrade(createTrade(timestamp = 2, price = BigDecimal("10000"), quantity = BigDecimal("2")))
        processor.processTrade(createTrade(timestamp = 3, price = BigDecimal("10000"), quantity = BigDecimal("3"))) // triggers recalc

        // Should have saved a VolumeWindow (3 trades processed, slideStep=3)
        verify(exactly = 1) { dao.saveVolumeWindow(any()) }
    }

    @Test
    fun `empty window does not crash statistics`() {
        val processor = VolumeFilterProcessor(dao, windowSize = 10, slideStep = 1, filterPercentile)

        processor.processTrade(createTrade())
        // Processed 1 trade, slideStep=1 → recalculates with 1 element
        // Should not crash on median/stddev with single element
        verify(atLeast = 1) { dao.saveVolumeWindow(any()) }
    }

    @Test
    fun `processTrade enforces window size limit`() {
        val processor = VolumeFilterProcessor(dao, windowSize = 200, slideStep = 1, filterPercentile)

        repeat(210) { i ->
            processor.processTrade(createTrade(timestamp = i.toLong(), quantity = BigDecimal("1")))
        }

        val stats = processor.getStats()
        val window = stats["Binance_BTCUSDT"] as Map<*, *>
        val ws = (window["windowSize"] as Int)
        assertTrue(ws in 100..200, "Window around chunk boundary, got $ws")
    }

    @Test
    fun `filtered trade saved when volume exceeds threshold`() {
        val processor = VolumeFilterProcessor(dao, windowSize = 5, slideStep = 1, filterPercentile = 0.8)

        // Feed trades with significantly different volumes to create a high threshold
        processor.processTrade(createTrade(timestamp = 1, quantity = BigDecimal("1")))
        processor.processTrade(createTrade(timestamp = 2, quantity = BigDecimal("1.5")))
        processor.processTrade(createTrade(timestamp = 3, quantity = BigDecimal("2")))
        processor.processTrade(createTrade(timestamp = 4, quantity = BigDecimal("100")))
        processor.processTrade(createTrade(timestamp = 5, quantity = BigDecimal("2.5")))

        // The 100.0 volume trade should have been above threshold at its time
        processor.flushFilteredTrades()
        verify(atLeast = 1) { dao.insertFilteredTradesBatch(any()) }
    }

    @Test
    fun `getStats returns per-instrument data`() {
        val processor = VolumeFilterProcessor(dao, windowSize, slideStep, filterPercentile)

        processor.processTrade(createTrade(symbol = "BTCUSDT"))
        processor.processTrade(createTrade(symbol = "ETHUSDT"))

        val stats = processor.getStats()
        assertEquals(2, stats.size)
        assertTrue(stats.containsKey("Binance_BTCUSDT"))
        assertTrue(stats.containsKey("Binance_ETHUSDT"))
    }

    @Test
    fun `shouldRecalculateWindow returns true exactly at slideStep boundaries`() {
        val processor = VolumeFilterProcessor(dao, windowSize = 100, slideStep = 5, filterPercentile)

        repeat(4) { processor.processTrade(createTrade(timestamp = it.toLong())) }
        // 4 trades processed, no VolumeWindow saved yet
        verify(exactly = 0) { dao.saveVolumeWindow(any()) }

        processor.processTrade(createTrade(timestamp = 5L))
        verify(exactly = 1) { dao.saveVolumeWindow(any()) }
    }
}
