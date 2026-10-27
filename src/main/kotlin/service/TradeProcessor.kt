/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.config.ProcessorConfig
import com.aandios.exchange.ExchangeAdapter
import com.aandios.exchange.ExchangeAdapterFactory
import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.CoroutineScope
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger {}

class TradeProcessor(
    private val dao: TradeDAO,
    private val config: ProcessorConfig = ProcessorConfig(),
    private val diskBuffer: DiskBuffer? = null,
    private val deadLetterQueue: DeadLetterQueue? = null,
    private val minuteBuffer: MinuteBuffer? = null
) {
    private lateinit var batchProcessor: BatchProcessor

    private val totalTrades = AtomicLong(0)
    private val tradesPerSecond = AtomicInteger(0)
    private val droppedTrades = AtomicLong(0)
    private val lastSecond = AtomicLong(System.currentTimeMillis() / 1000)
    private val lastTotalTrades = AtomicLong(0)

    private val instrumentStats = ConcurrentHashMap<String, InstrumentStats>()
    private val adapterCache = ConcurrentHashMap<String, ExchangeAdapter>()

    data class InstrumentStats(
        val totalTrades: AtomicLong = AtomicLong(0),
        val lastTradeTime: AtomicLong = AtomicLong(0),
        val batchQueueSize: AtomicInteger = AtomicInteger(0)
    )

    fun initialize(scope: CoroutineScope) {
        batchProcessor = BatchProcessor(
            dao = dao,
            batchSize = config.batchSize,
            flushIntervalMs = config.flushIntervalMs,
            diskBuffer = diskBuffer,
            maxQueueSize = config.queueCapacity
        )
        batchProcessor.start(scope)

        log.info { "TradeProcessor initialized | batch=${config.batchSize} queue=${config.queueCapacity}" }
    }

    fun process(json: String, exchange: String, symbol: String) {
        try {
            val trade = getAdapter(exchange).parseTrade(json, symbol)
            if (trade != null) process(trade)
        } catch (e: Exception) {
            log.error(e) { "Process error: $exchange/$symbol" }
            deadLetterQueue?.push(json, exchange, symbol, e.message ?: "unknown")
        }
    }

    fun process(node: JsonNode, exchange: String, symbol: String) {
        try {
            val trade = getAdapter(exchange).parseTradeNode(node, symbol)
            if (trade != null) process(trade)
        } catch (e: Exception) {
            log.error(e) { "Process node error: $exchange/$symbol" }
            deadLetterQueue?.push(node.toString(), exchange, symbol, e.message ?: "unknown")
        }
    }

    fun process(trade: Trade) {
        totalTrades.incrementAndGet()
        updateTps()

        val stats = instrumentStats.getOrPut(trade.key) { InstrumentStats() }
        stats.totalTrades.incrementAndGet()
        stats.lastTradeTime.set(System.currentTimeMillis())
        minuteBuffer?.addTrade(trade)

        if (!batchProcessor.addTrade(trade)) {
            if (diskBuffer == null || !diskBuffer.saveBatch(listOf(trade))) {
                droppedTrades.incrementAndGet()
                log.warn { "Queue full and WAL unavailable; dropping ${trade.key}" }
            }
        }
        stats.batchQueueSize.set(batchProcessor.getQueueSize(trade.key))
    }

    private fun getAdapter(exchange: String): ExchangeAdapter {
        return adapterCache.getOrPut(exchange) {
            ExchangeAdapterFactory.createAdapter(exchange)
        }
    }

    private fun updateTps() {
        val currentSecond = System.currentTimeMillis() / 1000
        val previousSecond = lastSecond.get()
        if (currentSecond != previousSecond && lastSecond.compareAndSet(previousSecond, currentSecond)) {
            val total = totalTrades.get()
            tradesPerSecond.set((total - lastTotalTrades.getAndSet(total)).toInt())
        }
    }

    fun getMetrics(): Map<String, Any> {
        updateTps()
        return mapOf(
            "totalTrades" to totalTrades.get(),
            "tradesPerSecond" to tradesPerSecond.get(),
            "batchQueueSize" to batchProcessor.getTotalQueueSize(),
            "instruments" to instrumentStats.size,
            "droppedTrades" to droppedTrades.get(),
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
