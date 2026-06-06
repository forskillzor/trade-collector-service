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
 * Batch-обработчик: каждые 60 секунд проверяет наступление минутных границ
 * и строит агрегаты + статистику для всех инструментов из raw_trades в БД.
 *
 * Горячий цикл WebSocket только пишет raw_trades, вся тяжёлая обработка — здесь.
 */
class BatchScheduler(
    private val dao: TradeDAO,
    private val symbols: List<String>,
    private val config: ProcessorConfig
) {
    private val watermarks = mutableMapOf<String, Long>() // "symbol_timeframe" → lastProcessedEndTime
    private var job: Job? = null
    private var last15mProcessed = 0L

    fun start(scope: CoroutineScope) {
        // Вычислить водяные знаки из существующих агрегатов
        symbols.forEach { symbol ->
            listOf("1m", "15m").forEach { tf ->
                val wm = getWatermark(symbol, tf)
                watermarks["${symbol}_$tf"] = wm
                log.info { "Batch watermark $symbol/$tf = ${if (wm <= 0) "start" else formatTime(wm)}" }
            }
        }

        job = scope.launch {
            while (isActive) {
                try {
                    processMinuteBoundaries()
                } catch (e: Exception) {
                    log.error(e) { "Batch cycle error" }
                }
                delay(1000) // проверка раз в секунду
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private fun getWatermark(symbol: String, timeframe: String): Long {
        return try {
            dao.connection.use { conn ->
                val tbl = "aggregates_${symbol.lowercase()}"
                conn.prepareStatement(
                    "SELECT COALESCE(MAX(end_time), -1) FROM $tbl WHERE timeframe = ?"
                ).use { stmt ->
                    stmt.setString(1, timeframe)
                    val rs = stmt.executeQuery()
                    if (rs.next()) rs.getLong(1) else -1L
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private fun processMinuteBoundaries() {
        val now = System.currentTimeMillis()
        val currentMinute = now / 60_000 * 60_000
        val current15m = now / 900_000 * 900_000

        symbols.forEach { symbol ->
            processSymbol(symbol, currentMinute)
        }

        // 15m — на границе каждые 15 минут
        if (current15m > last15mProcessed) {
            symbols.forEach { symbol -> build15mAggregate(symbol, current15m) }
            last15mProcessed = current15m
        }
    }

    private fun processSymbol(symbol: String, currentMinute: Long) {
        val key = "${symbol}_1m"
        var wm = watermarks[key] ?: (currentMinute - 60_000)

        // If no data yet (wm == -1), start from the minute just before current
        if (wm <= 0) wm = currentMinute - 60_000

        // Догоняем пропущенные минуты (после рестарта или долгой паузы)
        while (wm < currentMinute) {
            val start = wm
            val end = start + 60_000

            try {
                val trades = getTradesInRange(symbol, start, end)
                if (trades.isNotEmpty()) {
                    buildAndSave1mAggregate(symbol, start, end, trades)
                } else {
                    // Пустая свеча — сохраняем для непрерывности
                    saveEmptyAggregate(symbol, "1m", start, end)
                }

                // Пересчитываем volume-статистику из последних 10K сделок
                val recentTrades = dao.getRecentRawTrades("Binance", symbol, 10_000)
                if (recentTrades.isNotEmpty()) {
                    recalculateVolumeStats(symbol, recentTrades, start, end)
                }

                // Чистим старые raw_trades
                dao.cleanupOldRawTrades(symbol, 10_000)

            } catch (e: Exception) {
                log.warn(e) { "Batch failed for $symbol ${formatTime(start)}" }
            }

            wm = end
            watermarks[key] = wm
        }
    }

    private fun getTradesInRange(symbol: String, start: Long, end: Long): List<Trade> {
        val tbl = "raw_trades_${symbol.lowercase()}"
        return dao.connection.use { conn ->
            conn.prepareStatement(
                "SELECT timestamp, price, quantity, is_buy FROM $tbl WHERE timestamp >= ? AND timestamp < ? ORDER BY timestamp"
            ).use { stmt ->
                stmt.setLong(1, start)
                stmt.setLong(2, end)
                val rs = stmt.executeQuery()
                val trades = mutableListOf<Trade>()
                while (rs.next()) {
                    trades.add(Trade(
                        exchange = "Binance",
                        symbol = symbol,
                        timestamp = rs.getLong("timestamp"),
                        price = rs.getBigDecimal("price"),
                        quantity = rs.getBigDecimal("quantity"),
                        isBuy = rs.getBoolean("is_buy")
                    ))
                }
                trades
            }
        }
    }

    private fun buildAndSave1mAggregate(symbol: String, start: Long, end: Long, trades: List<Trade>) {
        val priceLevels = linkedMapOf<BigDecimal, PriceLevelData>()

        var minPrice = BigDecimal.ZERO
        var maxPrice = BigDecimal.ZERO

        trades.forEach { trade ->
            val p = trade.price
            val q = trade.quantity

            if (priceLevels.isEmpty()) { minPrice = p; maxPrice = p }
            else { if (p < minPrice) minPrice = p; if (p > maxPrice) maxPrice = p }

            val level = priceLevels.getOrPut(p) { PriceLevelData(p) }
            if (trade.isBuy) { level.bidVolume = level.bidVolume.add(q); level.bidCount++ }
            else { level.askVolume = level.askVolume.add(q); level.askCount++ }
        }

        val json = buildPriceLevelsJson(priceLevels.values.sortedBy { it.price })
        val candle = AggregateCandle(
            exchange = "Binance", symbol = symbol, timeframe = "1m",
            startTime = start, endTime = end,
            priceLevelsJson = json,
            totalTicks = trades.size.toLong(),
            minPrice = minPrice, maxPrice = maxPrice,
            priceLevels = priceLevels.size
        )
        dao.saveAggregate(candle)
        log.debug { "Batch aggregate $symbol 1m | ticks=${trades.size} levels=${priceLevels.size}" }
    }

    private fun saveEmptyAggregate(symbol: String, timeframe: String, start: Long, end: Long) {
        val candle = AggregateCandle(
            exchange = "Binance", symbol = symbol, timeframe = timeframe,
            startTime = start, endTime = end,
            priceLevelsJson = "[]", totalTicks = 0,
            minPrice = BigDecimal.ZERO, maxPrice = BigDecimal.ZERO,
            priceLevels = 0
        )
        dao.saveAggregate(candle)
    }

    private fun build15mAggregate(symbol: String, current15m: Long) {
        val start = current15m - 900_000
        val end = current15m

        try {
            val trades = getTradesInRange(symbol, start, end)
            if (trades.isNotEmpty()) {
                val priceLevels = linkedMapOf<BigDecimal, PriceLevelData>()
                var minPrice = BigDecimal.ZERO
                var maxPrice = BigDecimal.ZERO
                trades.forEach { trade ->
                    val p = trade.price; val q = trade.quantity
                    if (priceLevels.isEmpty()) { minPrice = p; maxPrice = p }
                    else { if (p < minPrice) minPrice = p; if (p > maxPrice) maxPrice = p }
                    val level = priceLevels.getOrPut(p) { PriceLevelData(p) }
                    if (trade.isBuy) { level.bidVolume = level.bidVolume.add(q); level.bidCount++ }
                    else { level.askVolume = level.askVolume.add(q); level.askCount++ }
                }
                val json = buildPriceLevelsJson(priceLevels.values.sortedBy { it.price })
                val candle = AggregateCandle(
                    exchange = "Binance", symbol = symbol, timeframe = "15m",
                    startTime = start, endTime = end,
                    priceLevelsJson = json,
                    totalTicks = trades.size.toLong(),
                    minPrice = minPrice, maxPrice = maxPrice,
                    priceLevels = priceLevels.size
                )
                dao.saveAggregate(candle)
                log.debug { "Batch aggregate $symbol 15m | ticks=${trades.size} levels=${priceLevels.size}" }
            }
            watermarks["${symbol}_15m"] = end
        } catch (e: Exception) {
            log.warn(e) { "Batch 15m failed for $symbol" }
        }
    }

    private fun recalculateVolumeStats(symbol: String, trades: List<Trade>, windowStart: Long, windowEnd: Long) {
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

        val volumeThreshold = BigDecimal.valueOf(digest.quantile(0.98))
        val window = VolumeWindow(
            exchange = "Binance", symbol = symbol,
            startTime = windowStart, endTime = windowEnd,
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
            filterPercentile = 0.98,
            filterThreshold = volumeThreshold
        )
        dao.saveVolumeWindow(window)

        // Обнаружение китовых сделок
        val batch = mutableListOf<FilteredTrade>()
        for (trade in trades) {
            val volUsd = trade.getVolumeUsd()
            if (volUsd >= volumeThreshold) {
                val category = when {
                    volUsd >= BigDecimal.valueOf(digest.quantile(0.995)) -> TradeCategory.WHALE
                    volUsd >= BigDecimal.valueOf(digest.quantile(0.99)) -> TradeCategory.VERY_LARGE
                    else -> TradeCategory.LARGE
                }
                batch.add(FilteredTrade(
                    trade = trade, volumeUsd = volUsd,
                    percentileThreshold = 0.98, volumeThreshold = volumeThreshold,
                    tradeCategory = category,
                    windowStartTime = windowStart, windowEndTime = windowEnd,
                    windowTotalTrades = trades.size
                ))
            }
        }
        if (batch.isNotEmpty()) {
            dao.insertFilteredTradesBatch(batch)
            log.debug { "Batch filtered $symbol: ${batch.size} large trades" }
        }
    }

    private fun buildPriceLevelsJson(levels: List<PriceLevelData>): String {
        if (levels.isEmpty()) return "[]"
        val sb = StringBuilder("[")
        levels.forEachIndexed { i, level ->
            if (i > 0) sb.append(',')
            sb.append("[")
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

    private fun formatTime(ms: Long): String {
        if (ms == 0L) return "none"
        return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.of("UTC")).toLocalTime().toString()
    }

    fun getWatermarks(): Map<String, Long> = watermarks.toMap()
}
