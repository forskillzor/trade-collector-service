package com.aandios.service

import com.aandios.config.ProcessorConfig
import com.aandios.model.*
import com.aandios.storage.postgres.TradeDAO
import com.tdunning.math.stats.MergingDigest
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.math.BigDecimal
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

/**
 * Строит footprint-агрегаты лестницы из raw_trades и сохраняет их в БД.
 *
 * 1m строится из сырых сделок, остальные таймфреймы — из ближайшего
 * меньшего таймфрейма. В БД сохраняются только закрытые бакеты.
 */
class BatchScheduler(
    private val buffer: MinuteBuffer,
    private val dao: TradeDAO,
    private val symbols: List<String>,
    private val config: ProcessorConfig,
    private val exchange: String = "Binance"
) {
    private val timeframes: List<Timeframe> =
        Timeframe.parse(config.timeframes).ifEmpty { Timeframe.ordered }
    private val watermarks = mutableMapOf<String, Long>()
    private val retryAfter = mutableMapOf<String, Long>()
    private var job: Job? = null
    private var lastCleanupAt = 0L

    fun start(scope: CoroutineScope) {
        symbols.forEach { symbol ->
            timeframes.forEach { timeframe ->
                val watermark = try {
                    dao.getAggregateWatermark(symbol, timeframe.code)
                } catch (e: Exception) {
                    log.warn(e) { "Failed to load ${symbol}/${timeframe.code} watermark" }
                    0L
                }
                watermarks[key(symbol, timeframe)] = watermark
                log.info {
                    "Batch watermark $symbol/${timeframe.code} = " +
                        if (watermark <= 0) "start" else formatTime(watermark)
                }
            }
        }
        log.info { "BatchScheduler started timeframes=${timeframes.joinToString { it.code }}" }

        job = scope.launch {
            try {
                catchUpAll(System.currentTimeMillis())
                while (isActive) {
                    try {
                        tick()
                    } catch (e: Exception) {
                        log.error(e) { "Batch cycle error" }
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error(e) { "BatchScheduler failed" }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun catchUpAll(now: Long) {
        symbols.forEach { symbol ->
            timeframes.forEach { timeframe ->
                if (timeframe == Timeframe.M1) {
                    process1m(symbol, now)
                } else {
                    catchUp(symbol, timeframe, now)
                }
            }
        }
    }

    private fun tick() {
        val now = System.currentTimeMillis()

        symbols.forEach { symbol ->
            timeframes.forEach { timeframe ->
                if (timeframe == Timeframe.M1) {
                    process1m(symbol, now)
                } else {
                    catchUp(symbol, timeframe, now)
                }
            }
        }

        val liquidationData = buffer.flush()
        processLiquidations(liquidationData.liquidations)

        if (now - lastCleanupAt >= CLEANUP_INTERVAL_MS) {
            symbols.forEach { symbol -> cleanupSymbol(symbol) }
            lastCleanupAt = now
        }
    }

    private fun process1m(symbol: String, now: Long) {
        val key = key(symbol, Timeframe.M1)
        val currentMinute = now / MINUTE_MS * MINUTE_MS
        var watermark = watermarks[key] ?: 0L
        if (watermark <= 0L) watermark = currentMinute - MINUTE_MS

        while (watermark < currentMinute) {
            val start = watermark
            val end = start + MINUTE_MS
            if (now < end + FLUSH_GRACE_MS) break

            try {
                val trades = getTradesInRange(symbol, start, end)
                if (trades.isNotEmpty()) {
                    build1mAggregate(symbol, start, end, trades)
                    if (end == currentMinute) {
                        val recentTrades = dao.getRecentRawTrades(exchange, symbol, 10_000)
                        if (recentTrades.isNotEmpty()) {
                            recalculateVolumeStats(symbol, recentTrades, start, end)
                        }
                    }
                } else {
                    saveEmptyAggregate(symbol, Timeframe.M1, start, end)
                }
                watermark = end
                watermarks[key] = watermark
            } catch (e: Exception) {
                log.warn(e) { "Batch 1m failed for $symbol ${formatTime(start)}" }
                break
            }
        }
    }

    private fun catchUp(symbol: String, timeframe: Timeframe, now: Long) {
        val source = timeframe.source ?: return
        val key = key(symbol, timeframe)
        if (now < (retryAfter[key] ?: 0L)) return

        val currentBucketStart = timeframe.bucketStart(now)
        val watermark = watermarks[key] ?: 0L
        if (watermark >= currentBucketStart) {
            retryAfter.remove(key)
            return
        }

        var cursor = if (watermark > 0L) {
            watermark
        } else {
            timeframe.bucketStart(now - INITIAL_BACKFILL_MS)
        }
        cursor = timeframe.bucketStart(cursor)

        var blocked = false
        while (cursor + timeframe.durationMs <= currentBucketStart) {
            val bucketStart = cursor
            val bucketEnd = bucketStart + timeframe.durationMs
            try {
                val sourceBars = dao.getAggregates(symbol, source.code, bucketStart, bucketEnd)
                val firstSourceStart = sourceBars.firstOrNull()?.startTime
                if (sourceBars.size < timeframe.sourceBarCount) {
                    if (firstSourceStart == null || firstSourceStart > bucketStart) {
                        log.debug {
                            "Skip ${symbol}/${timeframe.code} ${formatTime(bucketStart)}: " +
                                "source ${source.code} starts later"
                        }
                        cursor = bucketEnd
                        continue
                    }
                    blocked = true
                    break
                }
                val aggregate = AggregateBuilder.build(timeframe, sourceBars, bucketStart)
                if (aggregate == null) {
                    blocked = true
                    break
                }
                dao.saveAggregate(aggregate)
                watermarks[key] = bucketEnd
                cursor = bucketEnd
                log.debug {
                    "Batch aggregate $symbol ${timeframe.code} " +
                        "from ${sourceBars.size}x${source.code} | ticks=${aggregate.totalTicks}"
                }
            } catch (e: Exception) {
                log.warn(e) { "Batch ${timeframe.code} failed for $symbol ${formatTime(bucketStart)}" }
                blocked = true
                break
            }
        }

        if (blocked) {
            retryAfter[key] = now + retryDelay(timeframe)
        } else {
            watermarks[key] = cursor
            retryAfter.remove(key)
        }
    }

    private fun getTradesInRange(symbol: String, start: Long, end: Long): List<Trade> {
        val table = "raw_trades_${symbol.lowercase()}"
        return dao.connection.use { conn ->
            conn.prepareStatement(
                "SELECT exchange, timestamp, price, quantity, is_buy " +
                    "FROM $table WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp"
            ).use { stmt ->
                stmt.setLong(1, start)
                stmt.setLong(2, end)
                val rs = stmt.executeQuery()
                val trades = mutableListOf<Trade>()
                while (rs.next()) {
                    trades.add(
                        Trade(
                            exchange = rs.getString("exchange"),
                            symbol = symbol,
                            timestamp = rs.getLong("timestamp"),
                            price = rs.getBigDecimal("price"),
                            quantity = rs.getBigDecimal("quantity"),
                            isBuy = rs.getBoolean("is_buy")
                        )
                    )
                }
                trades
            }
        }
    }

    private fun build1mAggregate(symbol: String, start: Long, end: Long, trades: List<Trade>) {
        val priceLevels = linkedMapOf<BigDecimal, PriceLevelData>()
        var minPrice = BigDecimal.ZERO
        var maxPrice = BigDecimal.ZERO

        trades.forEach { trade ->
            val price = trade.price
            val quantity = trade.quantity

            if (priceLevels.isEmpty()) {
                minPrice = price
                maxPrice = price
            } else {
                if (price < minPrice) minPrice = price
                if (price > maxPrice) maxPrice = price
            }

            val level = priceLevels.getOrPut(price) { PriceLevelData(price) }
            if (trade.isBuy) {
                level.bidVolume = level.bidVolume.add(quantity)
                level.bidCount++
            } else {
                level.askVolume = level.askVolume.add(quantity)
                level.askCount++
            }
        }

        val candle = AggregateCandle(
            exchange = trades.firstOrNull()?.exchange ?: exchange,
            symbol = symbol,
            timeframe = Timeframe.M1.code,
            startTime = start,
            endTime = end,
            priceLevelsJson = buildPriceLevelsJson(priceLevels.values.sortedBy { it.price }),
            totalTicks = trades.size.toLong(),
            minPrice = minPrice,
            maxPrice = maxPrice,
            priceLevels = priceLevels.size
        )
        dao.saveAggregate(candle)
        log.debug { "Batch aggregate $symbol 1m | ticks=${trades.size} levels=${priceLevels.size}" }
    }

    private fun saveEmptyAggregate(symbol: String, timeframe: Timeframe, start: Long, end: Long) {
        val candle = AggregateCandle(
            exchange = exchange,
            symbol = symbol,
            timeframe = timeframe.code,
            startTime = start,
            endTime = end,
            priceLevelsJson = "[]",
            totalTicks = 0,
            minPrice = BigDecimal.ZERO,
            maxPrice = BigDecimal.ZERO,
            priceLevels = 0
        )
        dao.saveAggregate(candle)
    }

    private fun cleanupSymbol(symbol: String) {
        try {
            dao.cleanupOldRawTrades(symbol, 10_000)
            dao.cleanupOldDerivedData(symbol, AUX_RETENTION_MS)
            Timeframe.M1.retentionMs?.let {
                dao.cleanupOldAggregates(symbol, Timeframe.M1.code, it)
            }
            Timeframe.M5.retentionMs?.let {
                dao.cleanupOldAggregates(symbol, Timeframe.M5.code, it)
            }
        } catch (e: Exception) {
            log.warn(e) { "Cleanup failed for $symbol" }
        }
    }

    private fun recalculateVolumeStats(
        symbol: String,
        trades: List<Trade>,
        windowStart: Long,
        windowEnd: Long
    ) {
        if (trades.isEmpty()) return

        val digest = MergingDigest(100.0)
        var ewmaMean = 0.0
        var totalSum = 0.0
        var totalSumSq = 0.0
        var minVol = Double.MAX_VALUE
        var maxVol = 0.0
        val alpha = 1.0 / trades.size.coerceAtLeast(1)

        for (trade in trades) {
            val vol = trade.getVolumeUsd().toDouble()
            ewmaMean = alpha * vol + (1 - alpha) * ewmaMean
            digest.add(vol)
            totalSum += vol
            totalSumSq += vol * vol
            if (vol < minVol) minVol = vol
            if (vol > maxVol) maxVol = vol
        }

        val n = trades.size.toDouble()
        val avgVolume = totalSum / n
        val variance = ((totalSumSq / n) - (avgVolume * avgVolume)).coerceAtLeast(0.0)

        val volumeThreshold = BigDecimal.valueOf(digest.quantile(config.whalePercentile))
        val window = VolumeWindow(
            exchange = exchange,
            symbol = symbol,
            startTime = windowStart,
            endTime = windowEnd,
            totalTrades = trades.size,
            minVolume = BigDecimal.valueOf(minVol),
            maxVolume = BigDecimal.valueOf(maxVol),
            avgVolume = BigDecimal.valueOf(avgVolume),
            medianVolume = BigDecimal.valueOf(digest.quantile(0.5)),
            stddevVolume = BigDecimal.valueOf(sqrt(variance)),
            p50Volume = BigDecimal.valueOf(digest.quantile(0.5)),
            p95Volume = BigDecimal.valueOf(digest.quantile(0.95)),
            p98Volume = BigDecimal.valueOf(digest.quantile(0.98)),
            p99Volume = BigDecimal.valueOf(digest.quantile(0.99)),
            filterPercentile = config.whalePercentile,
            filterThreshold = volumeThreshold
        )
        dao.saveVolumeWindow(window)

        val batch = mutableListOf<FilteredTrade>()
        for (trade in trades) {
            val volUsd = trade.getVolumeUsd()
            if (volUsd >= volumeThreshold) {
                val category = when {
                    volUsd >= BigDecimal.valueOf(digest.quantile(0.995)) -> TradeCategory.WHALE
                    volUsd >= BigDecimal.valueOf(digest.quantile(0.99)) -> TradeCategory.VERY_LARGE
                    else -> TradeCategory.LARGE
                }
                batch.add(
                    FilteredTrade(
                        trade = trade,
                        volumeUsd = volUsd,
                        percentileThreshold = config.whalePercentile,
                        volumeThreshold = volumeThreshold,
                        tradeCategory = category,
                        windowStartTime = windowStart,
                        windowEndTime = windowEnd,
                        windowTotalTrades = trades.size
                    )
                )
            }
        }
        if (batch.isNotEmpty()) {
            dao.insertFilteredTradesBatch(batch)
            log.debug { "Batch filtered $symbol: ${batch.size} large trades" }
        }
    }

    private fun processLiquidations(liqs: Map<String, List<LiquidationOrder>>) {
        if (liqs.isEmpty()) return
        val end = System.currentTimeMillis() / MINUTE_MS * MINUTE_MS
        val start = end - MINUTE_MS

        liqs.forEach { (symbol, orders) ->
            if (orders.isEmpty()) return@forEach
            try {
                dao.insertLiquidationsBatch(symbol, orders)
                dao.saveLiquidationAggregate(symbol, start, orders)
                dao.cleanupOldLiquidations(symbol, LIQUIDATION_RETENTION_MS)
                log.debug { "Batch liquidations $symbol: ${orders.size} orders" }
            } catch (e: Exception) {
                log.warn(e) { "Batch liquidations failed for $symbol" }
            }
        }
    }

    private fun buildPriceLevelsJson(levels: List<PriceLevelData>): String =
        PriceLevelsJson.build(levels)

    private fun key(symbol: String, timeframe: Timeframe): String = "${symbol}_${timeframe.code}"

    private fun retryDelay(timeframe: Timeframe): Long =
        if (timeframe == Timeframe.D1) RETRY_DELAY_1D_MS else RETRY_DELAY_MS

    private fun formatTime(ms: Long): String {
        if (ms == 0L) return "none"
        return java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.of("UTC"))
            .toLocalTime()
            .toString()
    }

    fun getWatermarks(): Map<String, Long> = watermarks.toMap()

    companion object {
        private const val MINUTE_MS = 60_000L
        private const val FLUSH_GRACE_MS = 2_000L
        private const val INITIAL_BACKFILL_MS = DAY_MS
        private const val AUX_RETENTION_MS = DAY_MS
        private const val LIQUIDATION_RETENTION_MS = DAY_MS
        private const val CLEANUP_INTERVAL_MS = 3_600_000L
        private const val RETRY_DELAY_MS = 5_000L
        private const val RETRY_DELAY_1D_MS = 30_000L
    }
}
