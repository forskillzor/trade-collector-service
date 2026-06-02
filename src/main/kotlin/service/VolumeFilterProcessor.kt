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
    private val slidingWindows = ConcurrentHashMap<String, SlidingWindowStats>()
    private val processedTrades = ConcurrentHashMap<String, Long>()
    private val windowLocks = ConcurrentHashMap<String, Any>()
    private val filteredTradeBuffer = Collections.synchronizedList(mutableListOf<FilteredTrade>())
    private val filteredFlushSize = 100
    private val chunksTarget = 1000
    private val chunkSize = (windowSize / chunksTarget).coerceAtLeast(100)
    private val alpha: BigDecimal = BigDecimal.ONE.divide(BigDecimal(windowSize), 10, RoundingMode.HALF_UP)
    private val oneMinusAlpha: BigDecimal = BigDecimal.ONE.subtract(alpha)

    data class Chunk(
        var count: Int = 0,
        var sum: BigDecimal = BigDecimal.ZERO,
        var sumSquared: BigDecimal = BigDecimal.ZERO,
        val digest: MergingDigest = MergingDigest(100.0)
    ) {
        fun add(volume: BigDecimal) {
            count++
            sum = sum.add(volume)
            sumSquared = sumSquared.add(volume.multiply(volume))
            digest.add(volume.toDouble())
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
        var ewmaMean: BigDecimal = BigDecimal.ZERO,
        var ewmaVar: BigDecimal = BigDecimal.ZERO,
        var minVolume: BigDecimal = BigDecimal.ZERO,
        var maxVolume: BigDecimal = BigDecimal.ZERO,
        var digest: MergingDigest? = null,
        var volumeThreshold: BigDecimal = BigDecimal.ZERO,
        var windowStartTime: Long = 0,
        var windowEndTime: Long = 0
    )

    fun processTrade(trade: Trade) {
        val key = "${trade.exchange}_${trade.symbol}"
        val lock = windowLocks.getOrPut(key) { Any() }

        synchronized(lock) {
            if (!slidingWindows.containsKey(key)) {
                slidingWindows[key] = SlidingWindowStats(
                    exchange = trade.exchange,
                    symbol = trade.symbol,
                    windowStartTime = trade.timestamp
                )
                processedTrades[key] = 0L
            }

            val window = slidingWindows[key]!!
            val volumeUsd = trade.getVolumeUsd()

            // EWMA: старые данные decay-ят, свежие имеют больший вес
            window.ewmaMean = alpha.multiply(volumeUsd).add(oneMinusAlpha.multiply(window.ewmaMean))
            val diff = volumeUsd.subtract(window.ewmaMean)
            window.ewmaVar = alpha.multiply(diff.multiply(diff)).add(oneMinusAlpha.multiply(window.ewmaVar))

            // Track min/max
            if (window.totalTrades == 0) {
                window.minVolume = volumeUsd
                window.maxVolume = volumeUsd
            } else {
                if (volumeUsd < window.minVolume) window.minVolume = volumeUsd
                if (volumeUsd > window.maxVolume) window.maxVolume = volumeUsd
            }

            // Add to current chunk
            window.currentChunk.add(volumeUsd)
            window.totalTrades++
            window.windowEndTime = trade.timestamp

            // Flush chunk when full
            if (window.currentChunk.count >= chunkSize) {
                window.chunks.addLast(window.currentChunk)
                window.chunkedCount += window.currentChunk.count
                window.currentChunk = Chunk()
            }

            // Drop oldest chunks to maintain window size
            while (window.chunkedCount + window.currentChunk.count > windowSize && window.chunks.isNotEmpty()) {
                val dropped = window.chunks.removeFirst()
                window.chunkedCount -= dropped.count
                window.startIndex += dropped.count
            }

            processedTrades[key] = processedTrades[key]!! + 1

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
        if (window.totalTrades == 0) return

        // Merge all chunk digests + current
        val merged = MergingDigest(100.0)
        window.chunks.forEach { merged.add(it.digest) }
        merged.add(window.currentChunk.digest)
        window.digest = merged

        val totalSize = window.chunkedCount.toInt() + window.currentChunk.count
        if (totalSize == 0) return

        // Aggregate sum/sumSquared from chunks + current
        var totalSum = BigDecimal.ZERO
        var totalSumSq = BigDecimal.ZERO
        window.chunks.forEach {
            totalSum = totalSum.add(it.sum)
            totalSumSq = totalSumSq.add(it.sumSquared)
        }
        totalSum = totalSum.add(window.currentChunk.sum)
        totalSumSq = totalSumSq.add(window.currentChunk.sumSquared)

        val n = BigDecimal(totalSize)
        val avgVolume = totalSum.divide(n, 8, RoundingMode.HALF_UP)
        val medianVolume = BigDecimal.valueOf(merged.quantile(0.5))

        // Standard deviation from aggregate sums: Var = E[X²] - E[X]²
        val variance = try {
            val meanSquared = avgVolume.multiply(avgVolume)
            val meanOfSquares = totalSumSq.divide(n, 8, RoundingMode.HALF_UP)
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

        // Percentiles from merged digest
        val p50Volume = BigDecimal.valueOf(merged.quantile(0.5))
        val p95Volume = BigDecimal.valueOf(merged.quantile(0.95))
        val p98Volume = BigDecimal.valueOf(merged.quantile(filterPercentile))
        val p99Volume = BigDecimal.valueOf(merged.quantile(0.99))

        val volumeThreshold = BigDecimal.valueOf(merged.quantile(filterPercentile))
        window.volumeThreshold = volumeThreshold

        val volumeWindow = VolumeWindow(
            exchange = window.exchange,
            symbol = window.symbol,
            startTime = window.windowStartTime,
            endTime = window.windowEndTime,
            totalTrades = window.totalTrades,
            minVolume = window.minVolume,
            maxVolume = window.maxVolume,
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

        if (volumeUsd >= window.volumeThreshold) {
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
                "windowSize" to (window.chunkedCount + window.currentChunk.count).toInt(),
                "volumeThreshold" to window.volumeThreshold,
                "processedTrades" to (processedTrades[key] ?: 0L)
            )
        }
    }
}
