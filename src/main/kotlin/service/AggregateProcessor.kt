package com.aandios.service

import com.aandios.model.AggregateCandle
import com.aandios.model.PriceLevelData
import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.Float8Vector
import org.apache.arrow.vector.IntVector
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.ipc.ArrowStreamWriter
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.FieldType
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant

private val log = KotlinLogging.logger {}

class AggregateProcessor(
    private val dao: TradeDAO,
    private val timeframes: List<String> = listOf("1m", "5m", "1h"),
    private val outputDir: String = "./aggregates"
) {
    // todo how to get this allocator in aggregateCandleBuilder
    private val allocator = RootAllocator()
    private val activeCandles = mutableMapOf<String, MutableMap<String, AggregateCandleBuilder>>()

    init {
        Files.createDirectories(Paths.get(outputDir))
    }

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

            val price = BigDecimal.valueOf(trade.price)
            val quantity = BigDecimal.valueOf(trade.quantity)

            // Обновляем min/max цену
            if (minPrice == null || price < minPrice!!) minPrice = price
            if (maxPrice == null || price > maxPrice!!) maxPrice = price

            // Получаем или создаём уровень цены
            val levelData = priceLevels.getOrPut(price) {
                PriceLevelData(price)
            }

            // Обновляем объём в зависимости от направления
            if (trade.isBuy) {
                levelData.bidVolume = levelData.bidVolume.add(quantity)
                levelData.bidCount++
            } else {
                levelData.askVolume = levelData.askVolume.add(quantity)
                levelData.askCount++
            }
        }

        fun buildArrowData(): ByteBuffer {
            val childAllocator =
                this@AggregateProcessor.allocator.newChildAllocator("candle-builder", 0, Long.MAX_VALUE)


            // Создаем векторы для Arrow
            val priceVector = Float8Vector("price", childAllocator)
            val bidVolumeVector = Float8Vector("bid_volume", childAllocator)
            val askVolumeVector = Float8Vector("ask_volume", childAllocator)
            val bidCountVector = IntVector("bid_count", childAllocator)
            val askCountVector = IntVector("ask_count", childAllocator)

            try {
                // Устанавливаем емкость
                val size = priceLevels.size
                priceVector.allocateNew(size)
                bidVolumeVector.allocateNew(size)
                askVolumeVector.allocateNew(size)
                bidCountVector.allocateNew(size)
                askCountVector.allocateNew(size)

                // Заполняем данные (сортируем по цене)
                val sortedLevels = priceLevels.values.sortedBy { it.price }

                sortedLevels.forEachIndexed { index, level ->
                    val priceScaled = level.price.setScale(10, RoundingMode.HALF_UP)
                    val bidVolumeScaled = level.bidVolume.setScale(10, RoundingMode.HALF_UP)
                    val askVolumeScaled = level.askVolume.setScale(10, RoundingMode.HALF_UP)

                    priceVector.setSafe(index, level.price.toDouble())
                    bidVolumeVector.setSafe(index, level.bidVolume.toDouble())
                    askVolumeVector.setSafe(index, level.askVolume.toDouble())
                    bidCountVector.setSafe(index, level.bidCount)
                    askCountVector.setSafe(index, level.askCount)
                }

                // Устанавливаем количество записей
                priceVector.valueCount = size
                bidVolumeVector.valueCount = size
                askVolumeVector.valueCount = size
                bidCountVector.valueCount = size
                askCountVector.valueCount = size

                // Создаем Root вектор
                val root = VectorSchemaRoot.of(
                    priceVector, bidVolumeVector, askVolumeVector,
                    bidCountVector, askCountVector
                )

                // Записываем в ByteArray
                val outputStream = ByteArrayOutputStream()
                val writer = ArrowStreamWriter(root, null, Channels.newChannel(outputStream))

                writer.start()
                writer.writeBatch()
                writer.end()

                return ByteBuffer.wrap(outputStream.toByteArray())

            } finally {
                // Освобождаем ресурсы
                try {
                    priceVector.close()
                    bidVolumeVector.close()
                    askVolumeVector.close()
                    bidCountVector.close()
                    askCountVector.close()
                } catch (e: Exception) {
                    log.error(e) { "❌ Ошибка закрытия векторов" }
                }

                try {
                    childAllocator.close()
                } catch (e: Exception) {
                    log.error(e) { "❌ Ошибка закрытия аллокатора" }
                }
            }
        }

        fun buildAggregate(): AggregateCandle {
            val arrowData = buildArrowData()

            return AggregateCandle(
                exchange = exchange,
                symbol = symbol,
                timeframe = timeframe,
                startTime = startTime,
                endTime = endTime,
                arrowData = arrowData,
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

            // Получаем или создаем свечу
            val symbolCandles = activeCandles.getOrPut("${trade.exchange}_${trade.symbol}") {
                mutableMapOf()
            }

            var candleBuilder = symbolCandles[timeframe]

            // Если свечи нет или время вышло за пределы текущей свечи
            if (candleBuilder == null || trade.timestamp >= candleBuilder.endTime) {
                // Сохраняем предыдущую свечу если она есть
                if (candleBuilder != null) {
                    saveAggregate(candleBuilder)
                }

                // Создаем новую свечу
                candleBuilder = AggregateCandleBuilder(
                    exchange = trade.exchange,
                    symbol = trade.symbol,
                    timeframe = timeframe,
                    startTime = candleStart
                )
                symbolCandles[timeframe] = candleBuilder
            }

            // Добавляем сделку в свечу
            candleBuilder.addTrade(trade)
        }
    }

    private fun saveAggregate(builder: AggregateCandleBuilder) {
        val aggregate = builder.buildAggregate()
        val fileName = "${builder.exchange}_${builder.symbol}_${builder.timeframe}_" +
                "${builder.startTime}_${builder.endTime}.arrow"
        val filePath = Paths.get(outputDir, fileName).toString()

        dao.saveAggregate(aggregate, filePath)

        log.info {
            "📊 Агрегат сохранен: ${builder.exchange}/${builder.symbol} " +
                    "${builder.timeframe} (${builder.startTime}-${builder.endTime}) " +
                    "тиков=${builder.totalTicks}, уровней=${builder.priceLevels.size}"
        }
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