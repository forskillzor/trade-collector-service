/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiskBufferTest {

    @TempDir
    lateinit var tempDir: Path

    private val trade = Trade(
        exchange = "Binance",
        symbol = "BTCUSDT",
        timestamp = 1000L,
        price = BigDecimal("50000"),
        quantity = BigDecimal("1"),
        isBuy = true
    )

    @Test
    fun `saveBatch appends data to the same file`() {
        val buffer = DiskBuffer(tempDir.toString())

        assertTrue(buffer.saveBatch(listOf(trade)))
        assertTrue(buffer.saveBatch(listOf(trade.copy(timestamp = 1001L))))
        assertTrue(buffer.hasPending())
        assertTrue(buffer.size() > 0)
    }

    @Test
    fun `replayTo writes trades and removes the file`() {
        val dao = mockk<TradeDAO>(relaxed = true)
        val buffer = DiskBuffer(tempDir.toString())
        buffer.saveBatch(listOf(trade))

        assertTrue(buffer.replayTo(dao))
        verify(exactly = 1) { dao.insertRawTradesBatch(match { it.size == 1 }) }
        assertFalse(buffer.hasPending())
    }
}
