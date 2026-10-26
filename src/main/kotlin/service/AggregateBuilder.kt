/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.model.AggregateCandle
import com.aandios.model.PriceLevelData
import com.aandios.model.Timeframe
import java.math.BigDecimal

object AggregateBuilder {

    fun build(
        target: Timeframe,
        sourceAggregates: List<AggregateCandle>,
        bucketStart: Long
    ): AggregateCandle? {
        val source = target.source ?: return null
        val expectedCount = target.sourceBarCount
        if (sourceAggregates.size != expectedCount) return null

        val ordered = sourceAggregates.sortedBy { it.startTime }
        ordered.forEachIndexed { index, aggregate ->
            val expectedStart = bucketStart + index * source.durationMs
            if (aggregate.timeframe != source.code) return null
            if (aggregate.startTime != expectedStart) return null
            if (aggregate.endTime != expectedStart + source.durationMs) return null
        }

        val mergedLevels = linkedMapOf<BigDecimal, PriceLevelData>()
        var totalTicks = 0L
        var minPrice: BigDecimal? = null
        var maxPrice: BigDecimal? = null
        var exchange = ordered.first().exchange
        var symbol = ordered.first().symbol

        ordered.forEach { aggregate ->
            totalTicks += aggregate.totalTicks
            exchange = aggregate.exchange
            symbol = aggregate.symbol

            val levels = PriceLevelsJson.parse(aggregate.priceLevelsJson)
            levels.forEach { level ->
                val existing = mergedLevels.getOrPut(level.price) { PriceLevelData(level.price) }
                existing.bidVolume = existing.bidVolume.add(level.bidVolume)
                existing.askVolume = existing.askVolume.add(level.askVolume)
                existing.bidCount += level.bidCount
                existing.askCount += level.askCount
            }

            val aggregateMin = aggregate.minPrice.takeIf { it > BigDecimal.ZERO }
                ?: levels.minOfOrNull { it.price }
            val aggregateMax = aggregate.maxPrice.takeIf { it > BigDecimal.ZERO }
                ?: levels.maxOfOrNull { it.price }
            if (aggregateMin != null) {
                minPrice = minPrice?.min(aggregateMin) ?: aggregateMin
            }
            if (aggregateMax != null) {
                maxPrice = maxPrice?.max(aggregateMax) ?: aggregateMax
            }
        }

        val levels = mergedLevels.values.sortedBy { it.price }
        return AggregateCandle(
            exchange = exchange,
            symbol = symbol,
            timeframe = target.code,
            startTime = bucketStart,
            endTime = bucketStart + target.durationMs,
            priceLevelsJson = PriceLevelsJson.build(levels),
            totalTicks = totalTicks,
            minPrice = minPrice ?: BigDecimal.ZERO,
            maxPrice = maxPrice ?: BigDecimal.ZERO,
            priceLevels = levels.size
        )
    }
}
