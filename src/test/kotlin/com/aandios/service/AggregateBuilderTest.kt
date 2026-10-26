package com.aandios.service

import com.aandios.model.AggregateCandle
import com.aandios.model.PriceLevelData
import com.aandios.model.Timeframe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AggregateBuilderTest {

    private val bucketStart = Timeframe.M5.bucketStart(1_700_000_000_000L)

    @Test
    fun `merges five one-minute aggregates into one five-minute aggregate`() {
        val source = listOf(
            minute(0, "100", "1", "2"),
            minute(1, "100", "3", "4"),
            minute(2, "101", "5", "6"),
            minute(3, "99", "7", "8"),
            minute(4, "100", "10", "9")
        )

        val result = AggregateBuilder.build(Timeframe.M5, source, bucketStart)

        assertNotNull(result)
        assertEquals("5m", result.timeframe)
        assertEquals(bucketStart, result.startTime)
        assertEquals(bucketStart + Timeframe.M5.durationMs, result.endTime)
        assertEquals(50, result.totalTicks)
        assertEquals(3, result.priceLevels)
        assertEquals(0, result.minPrice.compareTo(BigDecimal("99")))
        assertEquals(0, result.maxPrice.compareTo(BigDecimal("101")))

        val levels = PriceLevelsJson.parse(result.priceLevelsJson)
        assertEquals(listOf("99", "100", "101"), levels.map { it.price.toPlainString() })
        val level100 = levels.first { it.price.compareTo(BigDecimal("100")) == 0 }
        assertEquals(0, level100.bidVolume.compareTo(BigDecimal("14")))
        assertEquals(0, level100.askVolume.compareTo(BigDecimal("15")))
        assertEquals(3, level100.bidCount)
        assertEquals(3, level100.askCount)
    }

    @Test
    fun `rejects incomplete source bucket`() {
        val source = (0 until 4).map { minute(it, "100", "1", "1") }

        assertNull(AggregateBuilder.build(Timeframe.M5, source, bucketStart))
    }

    @Test
    fun `rejects source with wrong timeframe`() {
        val source = (0 until 5).map { minute(it, "100", "1", "1") }
            .map { it.copy(timeframe = "1h") }

        assertNull(AggregateBuilder.build(Timeframe.M5, source, bucketStart))
    }

    @Test
    fun `rejects source with a gap`() {
        val source = listOf(
            minute(0, "100", "1", "1"),
            minute(1, "100", "1", "1"),
            minute(2, "100", "1", "1"),
            minute(4, "100", "1", "1")
        )

        assertNull(AggregateBuilder.build(Timeframe.M5, source, bucketStart))
    }

    @Test
    fun `keeps empty bucket continuous`() {
        val source = (0 until 5).map {
            AggregateCandle(
                exchange = "Binance",
                symbol = "BTCUSDT",
                timeframe = "1m",
                startTime = bucketStart + it * 60_000L,
                endTime = bucketStart + (it + 1) * 60_000L,
                priceLevelsJson = "[]",
                totalTicks = 0,
                minPrice = BigDecimal.ZERO,
                maxPrice = BigDecimal.ZERO,
                priceLevels = 0
            )
        }

        val result = AggregateBuilder.build(Timeframe.M5, source, bucketStart)

        assertNotNull(result)
        assertEquals(0, result.totalTicks)
        assertEquals(0, result.priceLevels)
        assertEquals(0, result.minPrice.compareTo(BigDecimal.ZERO))
        assertEquals(0, result.maxPrice.compareTo(BigDecimal.ZERO))
        assertEquals("[]", result.priceLevelsJson)
    }

    @Test
    fun `one minute has no lower source`() {
        assertNull(AggregateBuilder.build(Timeframe.M1, emptyList(), bucketStart))
    }

    private fun minute(index: Int, price: String, bid: String, ask: String): AggregateCandle {
        val start = bucketStart + index * 60_000L
        val level = PriceLevelData(BigDecimal(price)).apply {
            bidVolume = BigDecimal(bid)
            askVolume = BigDecimal(ask)
            bidCount = 1
            askCount = 1
        }
        return AggregateCandle(
            exchange = "Binance",
            symbol = "BTCUSDT",
            timeframe = "1m",
            startTime = start,
            endTime = start + 60_000L,
            priceLevelsJson = PriceLevelsJson.build(listOf(level)),
            totalTicks = 10,
            minPrice = BigDecimal(price),
            maxPrice = BigDecimal(price),
            priceLevels = 1
        )
    }
}
