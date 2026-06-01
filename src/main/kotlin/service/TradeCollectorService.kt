package com.aandios.service

import com.aandios.config.AppConfig
import com.aandios.storage.postgres.TradeDAO
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

class TradeCollectorService(
    private val dao: TradeDAO,
    private val config: AppConfig
) {
    private val isRunning = AtomicBoolean(false)
    private val exchangeClients = mutableListOf<ExchangeClient>()
    private var tradeProcessor: TradeProcessor? = null
    private var monitoringServer: MonitoringServer? = null
    private var serviceJob: Job? = null
    private var coroutineScope: CoroutineScope? = null

    suspend fun start() {
        if (isRunning.getAndSet(true)) {
            log.warn { "Service already running" }
            return
        }

        try {
            log.info { "Initializing TradeCollectorService..." }

            coroutineScope = CoroutineScope(Dispatchers.Default)

            // Инициализация процессора
            tradeProcessor = TradeProcessor(dao, config.processor)
            tradeProcessor!!.initialize(coroutineScope!!)

            // Создание клиентов для бирж
            config.exchanges.filter { it.enabled }.forEach { exchangeConfig ->
                val client = ExchangeClient(exchangeConfig, tradeProcessor!!)
                exchangeClients.add(client)
            }

            log.info { "Created ${exchangeClients.size} clients" }

            // Запуск мониторинга
            if (config.monitoring.enableMetrics) {
                monitoringServer = MonitoringServer(
                    port = config.monitoring.httpPort,
                    tradeProcessor = tradeProcessor!!,
                    tradeDAO = dao,
                )
                monitoringServer?.start()
            }

            // Запуск всех клиентов
            exchangeClients.forEach { client ->
                coroutineScope!!.launch {
                    try {
                        client.start()
                    } catch (e: Exception) {
                        log.error(e) { "Client start error" }
                    }
                }
            }

            // Основной цикл мониторинга
            serviceJob = coroutineScope!!.launch {
                while (isRunning.get()) {
                    delay(10000) // Каждые 10 секунд
                    logStatus()
                }
            }

            log.info { "Service started successfully" }

        } catch (e: Exception) {
            log.error(e) { "Service start error" }
            stop()
        }
    }

    private fun logStatus() {
        val metrics = tradeProcessor?.getMetrics() ?: emptyMap()
        val memory = Runtime.getRuntime()

        log.info { "STATUS | ticks=${metrics["totalTrades"] ?: 0} tps=${metrics["tradesPerSecond"] ?: 0} queue=${metrics["batchQueueSize"] ?: 0} clients=${exchangeClients.size} mem_total=${memory.totalMemory() / 1024 / 1024}MB mem_free=${memory.freeMemory() / 1024 / 1024}MB" }
    }

    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        log.info { "Stopping TradeCollectorService..." }

        ShutdownChain()
            .step("TradeProcessor", 30_000) { tradeProcessor?.shutdown() }
            .step("ExchangeClients", 15_000) {
                exchangeClients.forEach { client -> client.stop() }
                exchangeClients.clear()
            }
            .step("MonitoringServer", 5_000) { monitoringServer?.stop() }
            .execute()

        // Отмена задач
        serviceJob?.cancel()
        serviceJob = null

        coroutineScope?.cancel()
        coroutineScope = null

        tradeProcessor = null
        monitoringServer = null

        log.info { "Service stopped" }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun getMetrics(): Map<String, Any> {
        val processorMetrics = tradeProcessor?.getMetrics() ?: emptyMap()

        return processorMetrics + mapOf(
            "exchangeClients" to exchangeClients.size,
            "isRunning" to isRunning.get(),
            "memoryUsedMB" to Runtime.getRuntime().totalMemory() / 1024 / 1024,
            "memoryFreeMB" to Runtime.getRuntime().freeMemory() / 1024 / 1024
        )
    }
}