/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.*

class BatchProcessorTest {

    private val dao = mockk<TradeDAO>(relaxed = true)
    private val batchSize = 3
    private val flushIntervalMs = 100L

    @AfterEach
    fun cleanup() {
        clearMocks(dao)
    }

    @Test
    fun `addTrade enqueues into per-instrument queue`() {
        val processor = BatchProcessor(dao, batchSize, flushIntervalMs)
        val trade = Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true)
        processor.addTrade(trade)

        assertEquals(1, processor.getTotalQueueSize())
        assertEquals(1, processor.getQueueSize("Binance_BTCUSDT"))
    }

    @Test
    fun `addTrade with different instruments creates separate queues`() {
        val processor = BatchProcessor(dao, batchSize, flushIntervalMs)
        processor.addTrade(Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true))
        processor.addTrade(Trade("Binance", "ETHUSDT", 1000L, BigDecimal("3500"), BigDecimal("1"), true))

        assertEquals(2, processor.getTotalQueueSize())
        assertEquals(1, processor.getQueueSize("Binance_BTCUSDT"))
        assertEquals(1, processor.getQueueSize("Binance_ETHUSDT"))
    }

    @Test
    fun `reaching batchSize triggers immediate flush`() {
        val processor = BatchProcessor(dao, batchSize, flushIntervalMs)

        processor.addTrade(Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true))
        processor.addTrade(Trade("Binance", "BTCUSDT", 1001L, BigDecimal("50000"), BigDecimal("2"), true))
        processor.addTrade(Trade("Binance", "BTCUSDT", 1002L, BigDecimal("50000"), BigDecimal("3"), true)) // triggers flush

        verify(exactly = 1) { dao.insertRawTradesBatch(match { it.size == 3 }) }
        assertEquals(0, processor.getQueueSize("Binance_BTCUSDT"))
    }

    @Test
    fun `on DAO failure trades are re-queued`() {
        val dao = mockk<TradeDAO>(relaxed = false)
        every { dao.insertRawTradesBatch(any()) } throws RuntimeException("DB down")

        val processor = BatchProcessor(dao, batchSize, flushIntervalMs)

        processor.addTrade(Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true))
        processor.addTrade(Trade("Binance", "BTCUSDT", 1001L, BigDecimal("50000"), BigDecimal("2"), true))
        processor.addTrade(Trade("Binance", "BTCUSDT", 1002L, BigDecimal("50000"), BigDecimal("3"), true)) // flush fails

        assertEquals(3, processor.getQueueSize("Binance_BTCUSDT"), "Trades should be re-queued after failure")
        verify(exactly = 1) { dao.insertRawTradesBatch(any()) }
    }

    @Test
    fun `stop flushes remaining trades`(): Unit = runTest {
        val processor = BatchProcessor(dao, 1000, flushIntervalMs)

        processor.start(this)
        processor.addTrade(Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true))
        processor.addTrade(Trade("Binance", "BTCUSDT", 1001L, BigDecimal("50000"), BigDecimal("2"), true))

        // trades haven't been flushed yet (batch not full, timer not elapsed)
        assertTrue(processor.getQueueSize("Binance_BTCUSDT") > 0)

        processor.stop()
        // after stop, remaining trades should be flushed
        verify(atLeast = 1) { dao.insertRawTradesBatch(any()) }
    }

    @Test
    fun `queue removed only after successful insert`() {
        val processor = BatchProcessor(dao, 1, flushIntervalMs) // batchSize=1 triggers immediate flush

        processor.addTrade(Trade("Binance", "BTCUSDT", 1000L, BigDecimal("50000"), BigDecimal("1"), true))

        assertEquals(0, processor.getQueueSize("Binance_BTCUSDT"), "Queue should be removed after successful flush")
        verify(exactly = 1) { dao.insertRawTradesBatch(any()) }
    }

    @Test
    fun `getQueueSize returns 0 for unknown instrument`() {
        val processor = BatchProcessor(dao, batchSize, flushIntervalMs)
        assertEquals(0, processor.getQueueSize("unknown"))
    }
}
