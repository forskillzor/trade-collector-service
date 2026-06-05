package com.aandios.service

import com.aandios.config.ProcessorConfig
import com.aandios.exchange.ExchangeAdapter
import com.aandios.exchange.ExchangeAdapterFactory
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
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
    private var coroutineScope: CoroutineScope? = null

    // Метрики
    private var totalTrades = 0L
    private var tradesPerSecond = 0
    private var lastSecond = System.currentTimeMillis() / 1000
    private var lastTotalTrades = 0L

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

        batchProcessor = BatchProcessor(dao, config.batchSize, config.flushIntervalMs, diskBuffer)
        batchProcessor.start(scope)

        log.info { "TradeProcessor initialized | batch=${config.batchSize}" }
    }

    fun process(json: String, exchange: String, symbol: String) {
        try {
            val adapter = getAdapter(exchange)
            val trade = adapter.parseTrade(json, symbol)

            if (trade != null) {
                totalTrades++
                updateTps()

                val stats = instrumentStats.getOrPut(trade.key) { InstrumentStats() }
                stats.totalTrades.incrementAndGet()
                stats.lastTradeTime.set(System.currentTimeMillis())

                // Сохраняем в raw_trades (батчами)
                batchProcessor.addTrade(trade)
                stats.batchQueueSize.set(batchProcessor.getQueueSize(trade.key))

                if (totalTrades % 1000 == 0L) {
                    log.debug { "tick #$totalTrades | tps=${tradesPerSecond}/s | ${exchange}/${trade.symbol} price=${trade.price} vol=${trade.getVolumeUsd()} queue=${stats.batchQueueSize}" }
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
        val currentSecond = System.currentTimeMillis() / 1000
        if (currentSecond != lastSecond) {
            tradesPerSecond = (totalTrades - lastTotalTrades).toInt()
            lastTotalTrades = totalTrades
            lastSecond = currentSecond
        }
    }

    fun getMetrics(): Map<String, Any> {
        return mapOf(
            "totalTrades" to totalTrades,
            "tradesPerSecond" to tradesPerSecond,
            "batchQueueSize" to batchProcessor.getTotalQueueSize(),
            "instruments" to instrumentStats.size,
            "timestamp" to System.currentTimeMillis()
        )
    }

    fun getInstrumentDetails(): Map<String, Map<String, Any>> {
        val result = mutableMapOf<String, Map<String, Any>>()

        instrumentStats.forEach { (key, stats) ->
            val symbol = key.substringAfter("_")

            result[symbol] = mapOf(
                "exchange" to key.substringBefore("_"),
                "symbol" to symbol,
                "totalTrades" to stats.totalTrades.get(),
                "lastTradeTime" to stats.lastTradeTime.get(),
                "batchQueueSize" to stats.batchQueueSize.get()
            )
        }
        return result
    }

    fun shutdown() {
        batchProcessor.stop()
        log.info { "TradeProcessor stopped" }
    }
}