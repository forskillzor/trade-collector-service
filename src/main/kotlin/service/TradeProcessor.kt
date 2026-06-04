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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

class TradeProcessor(
    private val dao: TradeDAO,
    private val config: ProcessorConfig = ProcessorConfig(),
    private val diskBuffer: DiskBuffer? = null,
    private val deadLetterQueue: DeadLetterQueue? = null
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
    private var lastFlushTime = 0L

    // Статистика по инструментам
    private val instrumentStats = ConcurrentHashMap<String, InstrumentStats>()
    private val adapterCache = mutableMapOf<String, ExchangeAdapter>()

    data class InstrumentStats(
        val totalTrades: AtomicLong = AtomicLong(0),
        val lastTradeTime: AtomicLong = AtomicLong(0),
        val batchQueueSize: AtomicInteger = AtomicInteger(0)
    )

    fun initialize(scope: CoroutineScope) {
        this.coroutineScope = scope

        // Инициализация компонентов
        batchProcessor = BatchProcessor(dao, config.batchSize, config.flushIntervalMs, diskBuffer)
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
                stats.totalTrades.incrementAndGet()
                stats.lastTradeTime.set(System.currentTimeMillis())

                // 1. Сохраняем в raw_trades (батчами)
                batchProcessor.addTrade(trade)
                stats.batchQueueSize.set(batchProcessor.getQueueSize(instrumentKey))

                // 2. Обрабатываем для фильтрации больших сделок
                volumeFilterProcessor.processTrade(trade)

                // 3. Обрабатываем для агрегации в свечи
                aggregateProcessor.processTrade(trade)

                if (totalTrades % 1000 == 0L) {
                    log.debug { "tick #$totalTrades | tps=${tradesPerSecond}/s | ${exchange}/${trade.symbol} price=${trade.price} vol=${trade.getVolumeUsd()} queue=${stats.batchQueueSize}" }
                }

                // Флаш агрегатов каждые 10 минут
                val now = System.currentTimeMillis()
                if (now - lastFlushTime > 10 * 60 * 1000) {
                    aggregateProcessor.flushAll()
                    lastFlushTime = now
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Process error: $exchange/$symbol" }
            deadLetterQueue?.push(json, exchange, symbol, e.message ?: "unknown")
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
        volumeFilterProcessor.flushFilteredTrades()
        aggregateProcessor.flushAll()
        log.info { "TradeProcessor stopped" }
    }
}