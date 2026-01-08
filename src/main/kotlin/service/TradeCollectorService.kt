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
            log.warn { "⚠️ Сервис уже запущен" }
            return
        }

        try {
            log.info { "🔧 Инициализация TradeCollectorService..." }

            coroutineScope = CoroutineScope(Dispatchers.Default)

            // Инициализация процессора
            tradeProcessor = TradeProcessor(dao, config.processor)
            tradeProcessor!!.initialize(coroutineScope!!)

            // Создание клиентов для бирж
            config.exchanges.filter { it.enabled }.forEach { exchangeConfig ->
                val client = ExchangeClient(exchangeConfig, tradeProcessor!!)
                exchangeClients.add(client)
            }

            log.info { "✅ Создано клиентов: ${exchangeClients.size}" }

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
                        log.error(e) { "❌ Ошибка при запуске клиента" }
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

            log.info { "✅ Сервис успешно запущен" }

        } catch (e: Exception) {
            log.error(e) { "❌ Ошибка при запуске сервиса" }
            stop()
        }
    }

    private fun logStatus() {
        val metrics = tradeProcessor?.getMetrics() ?: emptyMap()
        val memory = Runtime.getRuntime()

        log.info {
            """
            📊 === Статус сервиса ===
            📈 Обработано тиков: ${metrics["totalTrades"] ?: 0}
            ⚡ TPS: ${metrics["tradesPerSecond"] ?: 0}
            📦 Очередь БД: ${metrics["batchQueueSize"] ?: 0}
            🔌 Клиентов: ${exchangeClients.size}
            💾 Память: ${memory.totalMemory() / 1024 / 1024} MB
            🆓 Свободно: ${memory.freeMemory() / 1024 / 1024} MB
            =====================
            """.trimIndent()
        }
    }

    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        log.info { "🛑 Остановка TradeCollectorService..." }

        // Остановка процессора
        tradeProcessor?.shutdown()

        // Остановка клиентов
        exchangeClients.forEach { client ->
            try {
                client.stop()
            } catch (e: Exception) {
                log.error(e) { "⚠️ Ошибка при остановке клиента" }
            }
        }
        exchangeClients.clear()

        // Остановка мониторинга
        monitoringServer?.stop()

        // Отмена задач
        serviceJob?.cancel()
        serviceJob = null

        coroutineScope?.cancel()
        coroutineScope = null

        tradeProcessor = null
        monitoringServer = null

        log.info { "✅ Сервис остановлен" }
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