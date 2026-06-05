package com.aandios.service

import com.aandios.model.*
import com.aandios.storage.postgres.TradeDAO
import com.tdunning.math.stats.MergingDigest
import mu.KotlinLogging
import java.math.BigDecimal
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

private val log = KotlinLogging.logger {}

class VolumeFilterProcessor(
    private val dao: TradeDAO,
    private val windowSize: Int = 10000,
    private val slideStep: Int = 1000,
    private val filterPercentile: Double = 0.98
) {
    private val slidingWindows = ConcurrentHashMap<String, SlidingWindowStats>()
    private val processedTrades = ConcurrentHashMap<String, Long>()
    private val windowLocks = ConcurrentHashMap<String, Any>()
    private val filteredTradeBuffer = Collections.synchronizedList(mutableListOf<FilteredTrade>())
    private val filteredFlushSize = 100
    private val chunksTarget = 100
    private val chunkSize = (windowSize / chunksTarget).coerceAtLeast(20)
    private val alpha: Double = 1.0 / windowSize
    private val oneMinusAlpha: Double = 1.0 - alpha
    private val initialized = ConcurrentHashMap.newKeySet<String>()

    data class Chunk(
        var count: Int = 0,
        var sum: Double = 0.0,
        var sumSquared: Double = 0.0,
        val digest: MergingDigest = MergingDigest(100.0)
    ) {
        fun add(volume: Double) {
            count++
            sum += volume
            sumSquared += volume * volume
            digest.add(volume)
        }
    }

    data class SlidingWindowStats(
        val exchange: String,
        val symbol: String,
        var startIndex: Long = 0,
        var chunks: ArrayDeque<Chunk> = ArrayDeque(),
        var currentChunk: Chunk = Chunk(),
        var chunkedCount: Long = 0,
        var totalTrades: Int = 0,
        var ewmaMean: Double = 0.0,
        var ewmaVar: Double = 0.0,
        var minVolume: Double = 0.0,
        var maxVolume: Double = 0.0,
        var digest: MergingDigest? = null,
        var volumeThreshold: BigDecimal = BigDecimal.ZERO,
        var windowStartTime: Long = 0,
        var windowEndTime: Long = 0
    )

    fun processTrade(trade: Trade) {
        val key = trade.key
        val lock = windowLocks.getOrPut(key) { Any() }

        synchronized(lock) {
            if (!slidingWindows.containsKey(key)) {
                val window = SlidingWindowStats(
                    exchange = trade.exchange,
                    symbol = trade.symbol,
                    windowStartTime = trade.timestamp
                )
                slidingWindows[key] = window
                processedTrades[key] = 0L
                initFromDbIfNeeded(key, window)
            }

            val window = slidingWindows[key]!!
            val volumeUsd = trade.getVolumeUsd().toDouble()

            window.ewmaMean = alpha * volumeUsd + oneMinusAlpha * window.ewmaMean
            val diff = volumeUsd - window.ewmaMean
            window.ewmaVar = alpha * diff * diff + oneMinusAlpha * window.ewmaVar

            if (window.totalTrades == 0) {
                window.minVolume = volumeUsd
                window.maxVolume = volumeUsd
            } else {
                if (volumeUsd < window.minVolume) window.minVolume = volumeUsd
                if (volumeUsd > window.maxVolume) window.maxVolume = volumeUsd
            }

            window.currentChunk.add(volumeUsd)
            window.totalTrades++
            window.windowEndTime = trade.timestamp

            if (window.currentChunk.count >= chunkSize) {
                window.chunks.addLast(window.currentChunk)
                window.chunkedCount += window.currentChunk.count
                window.currentChunk = Chunk()
            }

            while (window.chunkedCount + window.currentChunk.count > windowSize && window.chunks.isNotEmpty()) {
                val dropped = window.chunks.removeFirst()
                window.chunkedCount -= dropped.count
                window.startIndex += dropped.count
            }

            processedTrades[key] = processedTrades[key]!! + 1

            if (shouldRecalculateWindow(key)) {
                recalculateWindowStats(window)
                checkAndSaveFilteredTrade(trade, window, BigDecimal.valueOf(volumeUsd))
                dao.cleanupOldRawTrades(window.symbol, windowSize.toLong())
            }
        }
    }

    private fun initFromDbIfNeeded(key: String, window: SlidingWindowStats) {
        if (!initialized.add(key)) return
        try {
            val trades = dao.getRecentRawTrades(window.exchange, window.symbol, windowSize)
            if (trades.isEmpty()) return

            log.info { "Loaded ${trades.size} trades from DB for ${window.exchange}/${window.symbol}" }

            trades.forEach { trade ->
                val volumeUsd = trade.getVolumeUsd().toDouble()

                if (window.totalTrades == 0) {
                    window.minVolume = volumeUsd
                    window.maxVolume = volumeUsd
                    window.windowStartTime = trade.timestamp
                } else {
                    if (volumeUsd < window.minVolume) window.minVolume = volumeUsd
                    if (volumeUsd > window.maxVolume) window.maxVolume = volumeUsd
                }

                window.currentChunk.add(volumeUsd)
                window.totalTrades++
                window.windowEndTime = trade.timestamp

                if (window.currentChunk.count >= chunkSize) {
                    window.chunks.addLast(window.currentChunk)
                    window.chunkedCount += window.currentChunk.count
                    window.currentChunk = Chunk()
                }
            }

            recalculateWindowStats(window)
            log.info { "Init ${window.exchange}/${window.symbol}: ${window.totalTrades} trades, threshold=${window.volumeThreshold}" }
        } catch (e: Exception) {
            log.warn(e) { "DB init failed for $key" }
        }
    }

    private fun shouldRecalculateWindow(key: String): Boolean {
        val processedCount = processedTrades[key] ?: 0
        return processedCount % slideStep == 0L
    }

    private fun recalculateWindowStats(window: SlidingWindowStats) {
        if (window.totalTrades == 0) return

        val merged = MergingDigest(100.0)
        window.chunks.forEach { merged.add(it.digest) }
        merged.add(window.currentChunk.digest)
        window.digest = merged

        val totalSize = window.chunkedCount.toInt() + window.currentChunk.count
        if (totalSize == 0) return

        var totalSum = 0.0
        var totalSumSq = 0.0
        window.chunks.forEach {
            totalSum += it.sum
            totalSumSq += it.sumSquared
        }
        totalSum += window.currentChunk.sum
        totalSumSq += window.currentChunk.sumSquared

        val n = totalSize.toDouble()
        val avgVolume = totalSum / n
        val variance = ((totalSumSq / n) - (avgVolume * avgVolume)).coerceAtLeast(0.0)
        val stddevVolume = sqrt(variance)

        val p50Volume = BigDecimal.valueOf(merged.quantile(0.5))
        val p95Volume = BigDecimal.valueOf(merged.quantile(0.95))
        val p98Volume = BigDecimal.valueOf(merged.quantile(filterPercentile))
        val p99Volume = BigDecimal.valueOf(merged.quantile(0.99))
        val volumeThreshold = BigDecimal.valueOf(merged.quantile(filterPercentile))
        window.volumeThreshold = volumeThreshold

        val volumeWindow = VolumeWindow(
            exchange = window.exchange, symbol = window.symbol,
            startTime = window.windowStartTime, endTime = window.windowEndTime,
            totalTrades = window.totalTrades,
            minVolume = BigDecimal.valueOf(window.minVolume),
            maxVolume = BigDecimal.valueOf(window.maxVolume),
            avgVolume = BigDecimal.valueOf(avgVolume),
            medianVolume = p50Volume,
            stddevVolume = BigDecimal.valueOf(stddevVolume),
            p50Volume = p50Volume, p95Volume = p95Volume, p98Volume = p98Volume, p99Volume = p99Volume,
            filterPercentile = filterPercentile, filterThreshold = volumeThreshold
        )
        dao.saveVolumeWindow(volumeWindow)
    }

    private fun checkAndSaveFilteredTrade(trade: Trade, window: SlidingWindowStats, volumeUsd: BigDecimal) {
        if (window.volumeThreshold == BigDecimal.ZERO) return
        if (volumeUsd < window.volumeThreshold) return

        val digest = window.digest ?: return
        val category = when {
            volumeUsd >= BigDecimal.valueOf(digest.quantile(0.995)) -> TradeCategory.WHALE
            volumeUsd >= BigDecimal.valueOf(digest.quantile(0.99)) -> TradeCategory.VERY_LARGE
            else -> TradeCategory.LARGE
        }

        val filteredTrade = FilteredTrade(
            trade = trade, volumeUsd = volumeUsd,
            percentileThreshold = filterPercentile, volumeThreshold = window.volumeThreshold,
            tradeCategory = category,
            windowStartTime = window.windowStartTime, windowEndTime = window.windowEndTime,
            windowTotalTrades = window.totalTrades
        )
        filteredTradeBuffer.add(filteredTrade)
        if (filteredTradeBuffer.size >= filteredFlushSize) flushFilteredTrades()
    }

    fun flushFilteredTrades() {
        val batch = synchronized(filteredTradeBuffer) {
            if (filteredTradeBuffer.isEmpty()) return
            filteredTradeBuffer.toList().also { filteredTradeBuffer.clear() }
        }
        try {
            dao.insertFilteredTradesBatch(batch)
        } catch (e: Exception) {
            log.error(e) { "Failed to flush filtered trades, re-queued ${batch.size}" }
            filteredTradeBuffer.addAll(0, batch)
        }
    }

    fun getStats(): Map<String, Any> {
        return slidingWindows.mapValues { (_, window) ->
            mapOf(
                "totalTrades" to window.totalTrades,
                "windowSize" to (window.chunkedCount + window.currentChunk.count).toInt(),
                "volumeThreshold" to window.volumeThreshold,
                "processedTrades" to (processedTrades["${window.exchange}_${window.symbol}"] ?: 0L)
            )
        }
    }
}
