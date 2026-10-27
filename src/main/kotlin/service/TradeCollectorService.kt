/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.service

import com.aandios.BuildConfig
import com.aandios.config.AppConfig
import com.aandios.storage.postgres.TradeDAO
import com.aandios.telemetry.AdminServer
import com.aandios.telemetry.ServiceMetrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

class TradeCollectorService(
    private val dao: TradeDAO,
    private val config: AppConfig,
    private val scope: CoroutineScope,
    private val metrics: ServiceMetrics
) {
    private val isRunning = AtomicBoolean(false)
    private val termination = CompletableDeferred<Unit>()
    private val exchangeClients = mutableListOf<ExchangeClient>()
    private var tradeProcessor: TradeProcessor? = null
    private var batchScheduler: BatchScheduler? = null
    private var liquidationProcessor: LiquidationProcessor? = null
    private var adminServer: AdminServer? = null

    suspend fun start() {
        if (isRunning.getAndSet(true)) {
            log.warn { "Service already running" }
            return
        }

        try {
            log.info { "Initializing TradeCollectorService..." }

            val minuteBuffer = MinuteBuffer()
            val collectLiq = config.exchanges.any { it.collectLiquidations }
            liquidationProcessor = LiquidationProcessor(minuteBuffer, collectLiq)

            val dataDir = config.export.outputDir + "/buffer"
            val diskBuffer = DiskBuffer(dataDir)
            val deadLetterQueue = DeadLetterQueue(dataDir)

            tradeProcessor = TradeProcessor(
                dao,
                config.processor,
                diskBuffer,
                deadLetterQueue,
                minuteBuffer
            )
            tradeProcessor!!.initialize(scope)

            config.exchanges.filter { it.enabled }.forEach { exchangeConfig ->
                val client = ExchangeClient(exchangeConfig, tradeProcessor!!, liquidationProcessor)
                exchangeClients.add(client)
            }
            log.info { "Created ${exchangeClients.size} clients, liquidations=$collectLiq" }

            val allSymbols = config.exchanges.flatMap { it.symbols }.map { it.uppercase() }.distinct()
            val exchangeName = config.exchanges.firstOrNull { it.enabled }?.name ?: "Binance"
            batchScheduler = BatchScheduler(
                minuteBuffer,
                dao,
                allSymbols,
                config.processor,
                exchangeName
            )
            batchScheduler!!.start(scope)
            log.info { "BatchScheduler started for ${allSymbols.size} symbols on $exchangeName" }

            registerMetrics()

            if (config.monitoring.enableMetrics) {
                adminServer = AdminServer(
                    port = config.monitoring.httpPort,
                    host = config.monitoring.host,
                    metrics = metrics,
                    serviceName = "TradeCollectorService",
                    version = BuildConfig.VERSION,
                    readiness = {
                        dao.ping() && exchangeClients.any { it.isConnected() }
                    }
                )
                adminServer?.start()
            }

            exchangeClients.forEach { client ->
                scope.launch {
                    try {
                        client.start()
                    } catch (e: Exception) {
                        log.error(e) { "Client start error" }
                    }
                }
            }

            log.info { "Service started successfully" }
        } catch (e: Throwable) {
            log.error(e) { "Service start error" }
            stop()
            throw e
        }
    }

    private fun registerMetrics() {
        metrics.gauge(
            "trade_collector_trades_total",
            "Total number of processed trades"
        ) {
            tradeProcessor?.getMetrics()?.get("totalTrades") as? Number ?: 0
        }
        metrics.gauge(
            "trade_collector_tps",
            "Trades per second at the last measurement"
        ) {
            tradeProcessor?.getMetrics()?.get("tradesPerSecond") as? Number ?: 0
        }
        metrics.gauge(
            "trade_collector_batch_queue_size",
            "Trades waiting for the database writer"
        ) {
            tradeProcessor?.getMetrics()?.get("batchQueueSize") as? Number ?: 0
        }
        metrics.gauge(
            "trade_collector_instruments",
            "Number of instruments with processed trades"
        ) {
            tradeProcessor?.getMetrics()?.get("instruments") as? Number ?: 0
        }
        metrics.gauge(
            "trade_collector_dropped_total",
            "Trades dropped because both queue and WAL were unavailable"
        ) {
            tradeProcessor?.getMetrics()?.get("droppedTrades") as? Number ?: 0
        }
        metrics.gauge(
            "trade_collector_ws_connected",
            "Number of connected exchange WebSockets"
        ) {
            exchangeClients.count { it.isConnected() }
        }
        metrics.gauge(
            "trade_collector_ws_total",
            "Number of configured exchange WebSockets"
        ) {
            exchangeClients.size
        }
    }

    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            termination.complete(Unit)
            return
        }

        log.info { "Stopping TradeCollectorService..." }
        try {
            ShutdownChain()
                .step("ExchangeClients", 15_000) {
                    exchangeClients.forEach { client -> client.stop() }
                    exchangeClients.clear()
                }
                .step("BatchScheduler", 30_000) { batchScheduler?.stop() }
                .step("TradeProcessor", 30_000) { tradeProcessor?.shutdown() }
                .step("AdminServer", 5_000) { adminServer?.stop() }
                .execute()
        } finally {
            batchScheduler = null
            tradeProcessor = null
            liquidationProcessor = null
            adminServer = null
            termination.complete(Unit)
        }

        log.info { "Service stopped" }
    }

    suspend fun awaitTermination() {
        termination.await()
    }

    fun isRunning(): Boolean = isRunning.get()
}
