package com.aandios.service

import com.aandios.config.ProcessorConfig
import com.aandios.exchange.ExchangeAdapter
import com.aandios.exchange.ExchangeAdapterFactory
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import java.time.Instant
import kotlinx.coroutines.*
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class TradeProcessor(
    private val dao: TradeDAO,
    private val config: ProcessorConfig = ProcessorConfig()
) {
    private lateinit var batchProcessor: BatchProcessor
    private lateinit var volumeFilterProcessor: VolumeFilterProcessor
    private lateinit var aggregateProcessor: AggregateProcessor
    private var coroutineScope: CoroutineScope? = null

    // Метрики
    private var totalTrades = 0L
    private var tradesPerSecond = 0
    private var lastSecond = Instant.now().epochSecond
    private var lastTotalTrades = 0L
    private var lastCleanupTime = System.currentTimeMillis()

    // Статистика по инструментам
    private val instrumentStats = ConcurrentHashMap<String, InstrumentStats>()
    private val adapterCache = mutableMapOf<String, ExchangeAdapter>()

    data class InstrumentStats(
        var totalTrades: Long = 0,
        var lastTradeTime: Long = 0,
        var batchQueueSize: Int = 0
    )

    fun initialize(scope: CoroutineScope) {
        this.coroutineScope = scope

        // Инициализация компонентов
        batchProcessor = BatchProcessor(dao, config.batchSize, config.flushIntervalMs)
        batchProcessor.start(scope)

        volumeFilterProcessor = VolumeFilterProcessor(
            dao = dao,
            windowSize = config.windowSize,
            slideStep = config.slideStep,
            filterPercentile = config.filterPercentile
        )

        aggregateProcessor = AggregateProcessor(
            dao = dao,
            timeframes = config.timeframes
        )

        log.info { "TradeProcessor initialized | batch=${config.batchSize} window=${config.windowSize} perc=${config.filterPercentile}" }
    }

    fun process(json: String, exchange: String, symbol: String) {
        try {
            val adapter = getAdapter(exchange)
            val trade = adapter.parseTrade(json, symbol)

            if (trade != null) {
                totalTrades++
                updateTps()

                // Инициализация статистики инструмента
                val instrumentKey = "${exchange}_${symbol}"
                val stats = instrumentStats.getOrPut(instrumentKey) { InstrumentStats() }
                stats.totalTrades++
                stats.lastTradeTime = System.currentTimeMillis()

                // 1. Сохраняем в raw_trades (батчами)
                batchProcessor.addTrade(trade)
                stats.batchQueueSize = batchProcessor.getQueueSize(instrumentKey)

                // 2. Обрабатываем для фильтрации больших сделок
                volumeFilterProcessor.processTrade(trade)

                // 3. Обрабатываем для агрегации в свечи
                aggregateProcessor.processTrade(trade)

                if (totalTrades % 1000 == 0L) {
                    val volumeUsd = BigDecimal.valueOf(trade.getVolumeUsd())
                    log.debug { "tick #$totalTrades | tps=${tradesPerSecond}/s | ${exchange}/${trade.symbol} price=${trade.price} vol=$volumeUsd queue=${stats.batchQueueSize}" }
                }

                // Периодические операции
                val now = System.currentTimeMillis()
                if (now - lastCleanupTime > 5 * 60 * 1000) { // Каждые 5 минут
                    cleanupOldData()
                    lastCleanupTime = now
                }

                // Флаш агрегатов каждые 10 минут
                if (now % (10 * 60 * 1000) < 1000) {
                    aggregateProcessor.flushAll()
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Process error: $exchange/$symbol" }
        }
    }

    private fun getAdapter(exchange: String): ExchangeAdapter {
        return adapterCache.getOrPut(exchange) {
            ExchangeAdapterFactory.createAdapter(exchange)
        }
    }

    private fun updateTps() {
        val currentSecond = Instant.now().epochSecond
        if (currentSecond != lastSecond) {
            tradesPerSecond = (totalTrades - lastTotalTrades).toInt()
            lastTotalTrades = totalTrades
            lastSecond = currentSecond
        }
    }

    private fun cleanupOldData() {
        try {
            // Очищаем сырые сделки (храним только ~1 миллион)
            val deletedCount = dao.cleanupOldRawTrades()
            if (deletedCount > 0) {
                log.info { "Cleaned $deletedCount old raw_trades" }
            }
        } catch (e: Exception) {
            log.error(e) { "Raw trades cleanup error" }
        }
    }

    fun getMetrics(): Map<String, Any> {
        val filterStats = volumeFilterProcessor.getStats()

        return mapOf(
            "totalTrades" to totalTrades,
            "tradesPerSecond" to tradesPerSecond,
            "batchQueueSize" to batchProcessor.getTotalQueueSize(),
            "instruments" to instrumentStats.size,
            "filterStats" to filterStats,
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun shutdown() {
        batchProcessor.stop()
        aggregateProcessor.flushAll()
        log.info { "TradeProcessor stopped" }
    }
}