package com.aandios.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TimeframeTest {

    @Test
    fun `retention matches the current storage policy`() {
        assertEquals(30L * DAY_MS, Timeframe.M1.retentionMs)
        assertEquals(180L * DAY_MS, Timeframe.M5.retentionMs)
        assertNull(Timeframe.M15.retentionMs)
        assertNull(Timeframe.H1.retentionMs)
        assertNull(Timeframe.H4.retentionMs)
        assertNull(Timeframe.D1.retentionMs)
    }

    @Test
    fun `aggregation ladder uses nearest lower timeframe`() {
        assertNull(Timeframe.M1.source)
        assertEquals(Timeframe.M1, Timeframe.M5.source)
        assertEquals(5, Timeframe.M5.sourceBarCount)
        assertEquals(Timeframe.M1, Timeframe.M15.source)
        assertEquals(15, Timeframe.M15.sourceBarCount)
        assertEquals(Timeframe.M15, Timeframe.H1.source)
        assertEquals(4, Timeframe.H1.sourceBarCount)
        assertEquals(Timeframe.M15, Timeframe.H4.source)
        assertEquals(16, Timeframe.H4.sourceBarCount)
        assertEquals(Timeframe.M15, Timeframe.D1.source)
        assertEquals(96, Timeframe.D1.sourceBarCount)
    }

    @Test
    fun `bucket start aligns down to timeframe boundary`() {
        val timestamp = 1_700_000_123_456L
        assertEquals(timestamp / 60_000L * 60_000L, Timeframe.M1.bucketStart(timestamp))
        assertEquals(timestamp / 300_000L * 300_000L, Timeframe.M5.bucketStart(timestamp))
        assertEquals(0L, Timeframe.D1.bucketStart(Timeframe.D1.durationMs - 1))
    }

    @Test
    fun `parse keeps canonical order and ignores unknown codes`() {
        val parsed = Timeframe.parse(listOf("1d", "30m", "5m", "1m"))

        assertEquals(listOf(Timeframe.M1, Timeframe.M5, Timeframe.D1), parsed)
    }
}
