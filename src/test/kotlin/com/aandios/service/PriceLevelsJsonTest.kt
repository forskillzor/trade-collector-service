package com.aandios.service

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PriceLevelsJsonTest {

    @Test
    fun `parses canonical jsonb text with spaces`() {
        // Ровно то, что PostgreSQL jsonb отдаёт обратно: пробелы после запятых
        val text = "[[119.90000000, 0, 223.56000000, 0, 16], [119.91000000, 217.16000000, 1098.96000000, 8, 32]]"

        val levels = PriceLevelsJson.parse(text)

        assertEquals(2, levels.size)
        assertEquals(BigDecimal("119.90000000"), levels[0].price)
        assertEquals(BigDecimal.ZERO, levels[0].bidVolume)
        assertEquals(BigDecimal("223.56000000"), levels[0].askVolume)
        assertEquals(0, levels[0].bidCount)
        assertEquals(16, levels[0].askCount)
        assertEquals(BigDecimal("119.91000000"), levels[1].price)
        assertEquals(BigDecimal("217.16000000"), levels[1].bidVolume)
        assertEquals(BigDecimal("1098.96000000"), levels[1].askVolume)
        assertEquals(8, levels[1].bidCount)
        assertEquals(32, levels[1].askCount)
    }

    @Test
    fun `parses compact text without spaces`() {
        val text = "[[100.5,1,2,3,4],[101.5,5,6,7,8]]"

        val levels = PriceLevelsJson.parse(text)

        assertEquals(2, levels.size)
        assertEquals(BigDecimal("100.5"), levels[0].price)
        assertEquals(BigDecimal("101.5"), levels[1].price)
    }

    @Test
    fun `empty and broken json produce empty list`() {
        assertEquals(emptyList(), PriceLevelsJson.parse("[]"))
        assertEquals(emptyList(), PriceLevelsJson.parse(""))
        assertEquals(emptyList(), PriceLevelsJson.parse(null))
        assertEquals(emptyList(), PriceLevelsJson.parse("not json at all"))
        assertEquals(emptyList(), PriceLevelsJson.parse("[[119.9, 0], [oops]]"))
    }

    @Test
    fun `build and parse roundtrip`() {
        val levels = listOf(
            PriceLevelsJson.parse("[[119.90000000, 0, 223.56000000, 0, 16]]").first(),
            PriceLevelsJson.parse("[[120.50000000, 1.5, 2.5, 1, 2]]").first(),
        )

        val built = PriceLevelsJson.build(levels)
        val reparsed = PriceLevelsJson.parse(built)

        assertEquals(levels.map { it.price }, reparsed.map { it.price })
        assertEquals(levels.map { it.bidVolume }, reparsed.map { it.bidVolume })
        assertEquals(levels.map { it.askVolume }, reparsed.map { it.askVolume })
        assertEquals(levels.map { it.bidCount }, reparsed.map { it.bidCount })
        assertEquals(levels.map { it.askCount }, reparsed.map { it.askCount })
        assertTrue(built.startsWith("[["))
        assertTrue(built.endsWith("]]"))
    }
}
