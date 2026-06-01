package com.aandios.service

import com.aandios.model.*
import com.aandios.storage.postgres.TradeDAO
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
        var sortedVolumes: MutableList<BigDecimal> = mutableListOf(),
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
            window.totalTrades++
            window.windowEndTime = trade.timestamp

            // Поддерживаем размер окна
            if (window.volumes.size > windowSize) {
                window.volumes.removeFirst()
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

        // Копируем и сортируем объёмы
        window.sortedVolumes = window.volumes.sorted().toMutableList()

        // Рассчитываем статистику
        val size = window.sortedVolumes.size

        // Базовые статистики
        val minVolume = window.sortedVolumes.first()
        val maxVolume = window.sortedVolumes.last()
        val sumVolume = window.sortedVolumes.sumOf { it }
        val avgVolume = sumVolume.divide(BigDecimal(size), 8, RoundingMode.HALF_UP)

        // Медиана
        val medianVolume = if (size % 2 == 0) {
            val mid = size / 2
            window.sortedVolumes[mid - 1].add(window.sortedVolumes[mid])
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP)
        } else {
            window.sortedVolumes[size / 2]
        }

        // Стандартное отклонение
        val variance = window.sortedVolumes
            .map { value ->
                val diff = value.subtract(avgVolume)
                diff.multiply(diff)  // diff²
            }
            .fold(BigDecimal.ZERO) { acc, value -> acc.add(value) }
            .divide(BigDecimal(size), 8, RoundingMode.HALF_UP)

        val stddevVolume = try {
            if (variance >= BigDecimal.ZERO) {
                BigDecimal.valueOf(kotlin.math.sqrt(variance.toDouble()))
            } else {
                BigDecimal.ZERO
            }
        } catch (e: Exception) {
            BigDecimal.ZERO
        }

        if (size == 0) {
            log.warn { "Empty window for ${window.exchange}/${window.symbol}" }
            return
        }

        // Перцентили
        val p50Index = (size * 0.5).toInt().coerceAtMost(size - 1)
        val p95Index = (size * 0.95).toInt().coerceAtMost(size - 1)
        val p98Index = (size * filterPercentile).toInt().coerceAtMost(size - 1)
        val p99Index = (size * 0.99).toInt().coerceAtMost(size - 1)

        val p50Volume = window.sortedVolumes[p50Index]
        val p95Volume = window.sortedVolumes[p95Index]
        val p98Volume = window.sortedVolumes[p98Index]
        val p99Volume = window.sortedVolumes[p99Index]

        // Порог фильтрации
        val thresholdIndex = (size * filterPercentile).toInt().coerceAtMost(size - 1)
        val volumeThreshold = window.sortedVolumes[thresholdIndex]
        window.volumeThreshold = volumeThreshold

        // Сохраняем статистику окна в БД
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
            val category = when {
                volumeUsd >= window.sortedVolumes[(window.sortedVolumes.size * 0.995).toInt()] ->
                    TradeCategory.WHALE

                volumeUsd >= window.sortedVolumes[(window.sortedVolumes.size * 0.99).toInt()] ->
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