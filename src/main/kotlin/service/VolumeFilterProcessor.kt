package com.aandios.service

import com.aandios.model.*
import com.aandios.storage.postgres.TradeDAO
import com.tdunning.math.stats.MergingDigest
import mu.KotlinLogging
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

class VolumeFilterProcessor(
    private val dao: TradeDAO,
    private val windowSize: Int = 1000000,
    private val slideStep: Int = 100000,
    private val filterPercentile: Double = 0.98
) {
    private val slidingWindows = ConcurrentHashMap<String, SlidingWindowStats>() // ключ: "exchange_symbol"
    private val processedTrades = ConcurrentHashMap<String, Long>() // для отслеживания прогресса
    private val windowLocks = ConcurrentHashMap<String, Any>() // per-instrument синхронизация
    private val filteredTradeBuffer = Collections.synchronizedList(mutableListOf<FilteredTrade>())
    private val filteredFlushSize = 100

    data class SlidingWindowStats(
        val exchange: String,
        val symbol: String,
        var startIndex: Long = 0,
        var volumes: LinkedList<BigDecimal> = LinkedList(),
        var sum: BigDecimal = BigDecimal.ZERO,
        var sumSquared: BigDecimal = BigDecimal.ZERO,
        var digest: MergingDigest? = null,
        var totalTrades: Int = 0,
        var windowStartTime: Long = 0,
        var windowEndTime: Long = 0,
        var volumeThreshold: BigDecimal = BigDecimal.ZERO
    )

    fun processTrade(trade: Trade) {
        val key = "${trade.exchange}_${trade.symbol}"
        val lock = windowLocks.getOrPut(key) { Any() }

        synchronized(lock) {
            // Инициализируем окно если нужно
            if (!slidingWindows.containsKey(key)) {
                slidingWindows[key] = SlidingWindowStats(
                    exchange = trade.exchange,
                    symbol = trade.symbol,
                    windowStartTime = trade.timestamp
                )
                processedTrades[key] = 0L
            }

            val window = slidingWindows[key]!!

            // Добавляем объём в окно
            val volumeUsd = trade.getVolumeUsd()
            window.volumes.add(volumeUsd)
            window.sum = window.sum.add(volumeUsd)
            window.sumSquared = window.sumSquared.add(volumeUsd.multiply(volumeUsd))
            window.totalTrades++
            window.windowEndTime = trade.timestamp

            // Поддерживаем размер окна
            if (window.volumes.size > windowSize) {
                val removed = window.volumes.removeFirst()
                window.sum = window.sum.subtract(removed)
                window.sumSquared = window.sumSquared.subtract(removed.multiply(removed))
                window.startIndex++
            }

            // Обновляем обработанные сделки
            processedTrades[key] = processedTrades[key]!! + 1

            // Проверяем нужно ли сдвинуть окно и пересчитать статистику
            if (shouldRecalculateWindow(key)) {
                recalculateWindowStats(window)
                checkAndSaveFilteredTrade(trade, window, volumeUsd)
            }
        }
    }

    private fun shouldRecalculateWindow(key: String): Boolean {
        val processedCount = processedTrades[key] ?: 0
        return processedCount % slideStep == 0L
    }

    private fun recalculateWindowStats(window: SlidingWindowStats) {
        if (window.volumes.isEmpty()) return

        val size = window.volumes.size
        val n = BigDecimal(size)

        // Build t-digest
        val digest = MergingDigest(100.0)
        window.volumes.forEach { digest.add(it.toDouble()) }
        window.digest = digest

        // Statistics from running sums
        val minVolume = window.volumes.min()
        val maxVolume = window.volumes.max()
        val avgVolume = window.sum.divide(n, 8, RoundingMode.HALF_UP)

        // Median from t-digest
        val medianVolume = BigDecimal.valueOf(digest.quantile(0.5))

        // Standard deviation from running sums: Var = E[X²] - E[X]²
        val variance = try {
            val meanSquared = avgVolume.multiply(avgVolume)
            val meanOfSquares = window.sumSquared.divide(n, 8, RoundingMode.HALF_UP)
            val v = meanOfSquares.subtract(meanSquared)
            if (v >= BigDecimal.ZERO) v else BigDecimal.ZERO
        } catch (e: Exception) {
            BigDecimal.ZERO
        }

        val stddevVolume = try {
            BigDecimal.valueOf(sqrt(variance.toDouble()))
        } catch (e: Exception) {
            BigDecimal.ZERO
        }

        // Percentiles from t-digest
        val p50Volume = BigDecimal.valueOf(digest.quantile(0.5))
        val p95Volume = BigDecimal.valueOf(digest.quantile(0.95))
        val p98Volume = BigDecimal.valueOf(digest.quantile(filterPercentile))
        val p99Volume = BigDecimal.valueOf(digest.quantile(0.99))

        val volumeThreshold = BigDecimal.valueOf(digest.quantile(filterPercentile))
        window.volumeThreshold = volumeThreshold

        val volumeWindow = VolumeWindow(
            exchange = window.exchange,
            symbol = window.symbol,
            startTime = window.windowStartTime,
            endTime = window.windowEndTime,
            totalTrades = window.totalTrades,
            minVolume = minVolume,
            maxVolume = maxVolume,
            avgVolume = avgVolume,
            medianVolume = medianVolume,
            stddevVolume = stddevVolume,
            p50Volume = p50Volume,
            p95Volume = p95Volume,
            p98Volume = p98Volume,
            p99Volume = p99Volume,
            filterPercentile = filterPercentile,
            filterThreshold = volumeThreshold
        )

        dao.saveVolumeWindow(volumeWindow)

        log.debug { "Window ${window.exchange}/${window.symbol} | trades=${window.totalTrades} threshold=${volumeThreshold} 98p=${p98Volume}" }
    }

    private fun checkAndSaveFilteredTrade(
        trade: Trade,
        window: SlidingWindowStats,
        volumeUsd: BigDecimal
    ) {
        if (window.volumeThreshold == BigDecimal.ZERO) return

        // Проверяем превышает ли сделка порог
        if (volumeUsd >= window.volumeThreshold) {
            // Определяем категорию
            val digest = window.digest ?: return
            val category = when {
                volumeUsd >= BigDecimal.valueOf(digest.quantile(0.995)) ->
                    TradeCategory.WHALE

                volumeUsd >= BigDecimal.valueOf(digest.quantile(0.99)) ->
                    TradeCategory.VERY_LARGE

                else -> TradeCategory.LARGE
            }

            val filteredTrade = FilteredTrade(
                trade = trade,
                volumeUsd = volumeUsd,
                percentileThreshold = filterPercentile,
                volumeThreshold = window.volumeThreshold,
                tradeCategory = category,
                windowStartTime = window.windowStartTime,
                windowEndTime = window.windowEndTime,
                windowTotalTrades = window.totalTrades
            )

            // Сохраняем фильтрованную сделку батчом
            filteredTradeBuffer.add(filteredTrade)
            if (filteredTradeBuffer.size >= filteredFlushSize) {
                flushFilteredTrades()
            }

            log.info { "Large trade ${trade.exchange}/${trade.symbol} | ${category} vol=${volumeUsd} > threshold=${window.volumeThreshold}" }
        }
    }

    fun flushFilteredTrades() {
        val batch = synchronized(filteredTradeBuffer) {
            if (filteredTradeBuffer.isEmpty()) return
            filteredTradeBuffer.toList().also { filteredTradeBuffer.clear() }
        }
        try {
            dao.insertFilteredTradesBatch(batch)
            log.debug { "Flushed ${batch.size} filtered trades" }
        } catch (e: Exception) {
            log.error(e) { "Failed to flush filtered trades, re-queued ${batch.size}" }
            filteredTradeBuffer.addAll(0, batch)
        }
    }

    fun getStats(): Map<String, Any> {
        return slidingWindows.mapValues { (key, window) ->
            mapOf(
                "totalTrades" to window.totalTrades,
                "windowSize" to window.volumes.size,
                "volumeThreshold" to window.volumeThreshold,
                "processedTrades" to (processedTrades[key] ?: 0L)
            )
        }
    }
}