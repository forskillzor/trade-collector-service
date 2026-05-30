package com.aandios.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.time.Instant
import kotlin.test.assertEquals

class AggregateProcessorTest {

    @Test
    fun `calculateCandleStart - 1m rounds to minute boundary`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:34:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "1m"))
    }

    @Test
    fun `calculateCandleStart - 1m exact minute boundary unchanged`() {
        val ts = Instant.parse("2024-01-01T12:34:00Z").toEpochMilli()
        assertEquals(ts, AggregateProcessor.calculateCandleStart(ts, "1m"))
    }

    @Test
    fun `calculateCandleStart - 5m`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:30:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "5m"))
    }

    @Test
    fun `calculateCandleStart - 15m`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:30:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "15m"))
    }

    @Test
    fun `calculateCandleStart - 30m`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:30:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "30m"))
    }

    @Test
    fun `calculateCandleStart - 1h`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "1h"))
    }

    @Test
    fun `calculateCandleStart - 4h`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "4h"))
    }

    @Test
    fun `calculateCandleStart - 1d`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "1d"))
    }

    @Test
    fun `calculateCandleStart - midnight 1d boundary`() {
        val ts = Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()
        assertEquals(ts, AggregateProcessor.calculateCandleStart(ts, "1d"))
    }

    @Test
    fun `calculateCandleStart - unknown timeframe returns raw second*1000`() {
        val ts = Instant.parse("2024-01-01T12:34:56Z").toEpochMilli()
        val expected = Instant.parse("2024-01-01T12:34:56Z").epochSecond * 1000
        assertEquals(expected, AggregateProcessor.calculateCandleStart(ts, "unknown"))
    }

    @Test
    fun `calculateEndTime - all timeframes`() {
        val start = Instant.parse("2024-01-01T12:00:00Z").toEpochMilli()

        assertAll(
            { assertEquals(start + 60_000, AggregateProcessor.calculateEndTime(start, "1m")) },
            { assertEquals(start + 300_000, AggregateProcessor.calculateEndTime(start, "5m")) },
            { assertEquals(start + 900_000, AggregateProcessor.calculateEndTime(start, "15m")) },
            { assertEquals(start + 1_800_000, AggregateProcessor.calculateEndTime(start, "30m")) },
            { assertEquals(start + 3_600_000, AggregateProcessor.calculateEndTime(start, "1h")) },
            { assertEquals(start + 14_400_000, AggregateProcessor.calculateEndTime(start, "4h")) },
            { assertEquals(start + 86_400_000, AggregateProcessor.calculateEndTime(start, "1d")) },
            { assertEquals(start + 60_000, AggregateProcessor.calculateEndTime(start, "unknown")) }
        )
    }

    @Test
    fun `calculateCandleStart - epoch zero`() {
        assertEquals(0L, AggregateProcessor.calculateCandleStart(0, "1m"))
        assertEquals(0L, AggregateProcessor.calculateCandleStart(0, "1h"))
        assertEquals(0L, AggregateProcessor.calculateCandleStart(0, "1d"))
    }
}
