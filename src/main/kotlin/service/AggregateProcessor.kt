package com.aandios.service

import com.aandios.model.AggregateCandle
import com.aandios.model.PriceLevelData
import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import java.math.BigDecimal
import java.time.Instant

private val log = KotlinLogging.logger {}

class AggregateProcessor(
    private val dao: TradeDAO,
    private val timeframes: List<String> = listOf("1m", "5m", "1h")
) {
    private val activeCandles = mutableMapOf<String, MutableMap<String, AggregateCandleBuilder>>()

    inner class AggregateCandleBuilder(
        val exchange: String,
        val symbol: String,
        val timeframe: String,
        val startTime: Long
    ) {
        val endTime: Long = calculateEndTime(startTime, timeframe)
        val priceLevels = mutableMapOf<BigDecimal, PriceLevelData>()
        var totalTicks = 0L
        var minPrice: BigDecimal? = null
        var maxPrice: BigDecimal? = null

        fun addTrade(trade: Trade) {
            totalTicks++

            val price = trade.price
            val quantity = trade.quantity

            if (minPrice == null || price < minPrice!!) minPrice = price
            if (maxPrice == null || price > maxPrice!!) maxPrice = price

            val levelData = priceLevels.getOrPut(price) {
                PriceLevelData(price)
            }

            if (trade.isBuy) {
                levelData.bidVolume = levelData.bidVolume.add(quantity)
                levelData.bidCount++
            } else {
                levelData.askVolume = levelData.askVolume.add(quantity)
                levelData.askCount++
            }
        }

        fun buildPriceLevelsJson(): String {
            val sortedLevels = priceLevels.values.sortedBy { it.price }
            val sb = StringBuilder()
            sb.append('[')
            sortedLevels.forEachIndexed { i, level ->
                if (i > 0) sb.append(',')
                sb.append('[')
                sb.append(level.price.toPlainString()).append(',')
                sb.append(level.bidVolume.toPlainString()).append(',')
                sb.append(level.askVolume.toPlainString()).append(',')
                sb.append(level.bidCount).append(',')
                sb.append(level.askCount)
                sb.append(']')
            }
            sb.append(']')
            return sb.toString()
        }

        fun buildAggregate(): AggregateCandle {
            val priceLevelsJson = buildPriceLevelsJson()

            return AggregateCandle(
                exchange = exchange,
                symbol = symbol,
                timeframe = timeframe,
                startTime = startTime,
                endTime = endTime,
                priceLevelsJson = priceLevelsJson,
                totalTicks = totalTicks,
                minPrice = minPrice ?: BigDecimal.ZERO,
                maxPrice = maxPrice ?: BigDecimal.ZERO,
                priceLevels = priceLevels.size
            )
        }
    }

    fun processTrade(trade: Trade) {
        timeframes.forEach { timeframe ->
            val candleStart = calculateCandleStart(trade.timestamp, timeframe)

            val symbolCandles = activeCandles.getOrPut("${trade.exchange}_${trade.symbol}") {
                mutableMapOf()
            }

            var candleBuilder = symbolCandles[timeframe]

            if (candleBuilder == null || trade.timestamp >= candleBuilder.endTime) {
                if (candleBuilder != null) {
                    saveAggregate(candleBuilder)
                }

                candleBuilder = AggregateCandleBuilder(
                    exchange = trade.exchange,
                    symbol = trade.symbol,
                    timeframe = timeframe,
                    startTime = candleStart
                )
                symbolCandles[timeframe] = candleBuilder
            }

            candleBuilder.addTrade(trade)
        }
    }

    private fun saveAggregate(builder: AggregateCandleBuilder) {
        val aggregate = builder.buildAggregate()
        dao.saveAggregate(aggregate)

        log.info { "Aggregate ${builder.exchange}/${builder.symbol} ${builder.timeframe} | ticks=${builder.totalTicks} levels=${builder.priceLevels.size}" }
    }

    fun flushAll() {
        activeCandles.values.forEach { timeframeCandles ->
            timeframeCandles.values.forEach { candleBuilder ->
                saveAggregate(candleBuilder)
            }
        }
        activeCandles.clear()
    }

    companion object {
        fun calculateCandleStart(timestamp: Long, timeframe: String): Long {
            val instant = Instant.ofEpochMilli(timestamp)
            val seconds = instant.epochSecond

            return when (timeframe) {
                "1m" -> seconds - (seconds % 60)
                "5m" -> seconds - (seconds % 300)
                "15m" -> seconds - (seconds % 900)
                "30m" -> seconds - (seconds % 1800)
                "1h" -> seconds - (seconds % 3600)
                "4h" -> seconds - (seconds % 14400)
                "1d" -> seconds - (seconds % 86400)
                else -> seconds
            } * 1000
        }

        fun calculateEndTime(startTime: Long, timeframe: String): Long {
            return when (timeframe) {
                "1m" -> startTime + 60000
                "5m" -> startTime + 300000
                "15m" -> startTime + 900000
                "30m" -> startTime + 1800000
                "1h" -> startTime + 3600000
                "4h" -> startTime + 14400000
                "1d" -> startTime + 86400000
                else -> startTime + 60000
            }
        }
    }
}