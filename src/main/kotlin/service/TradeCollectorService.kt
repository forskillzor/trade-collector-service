package com.aandios.service

import com.aandios.config.ConfigManager
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

private val log = KotlinLogging.logger {}

class TradeCollectorService {
    private val isRunning = AtomicBoolean(false)
    private val exchangeClients = mutableListOf<ExchangeClient>()
    private var processor: TradeProcessor? = null
    private var monitoringServer: MonitoringServer? = null

    suspend fun start() {
        if (isRunning.getAndSet(true)) {
            log.warn { "Сервис уже запущен" }
            return
        }

        try {
            log.info { "Инициализация TradeCollectorService..." }

            // Загрузка конфигурации
            val config = ConfigManager.getConfig()

            // Инициализация процессора
            processor = TradeProcessor()

            // Создание клиентов для каждой биржи
            config.exchanges.filter { it.enabled }.forEach { exchangeConfig ->
                val client = ExchangeClient(exchangeConfig, processor!!)
                exchangeClients.add(client)
            }

            log.info { "Создано клиентов: ${exchangeClients.size}" }

            // Запуск мониторинга
            if (config.monitoring.enableMetrics) {
                monitoringServer = MonitoringServer(
                    port = config.monitoring.httpPort,
                    tradeProcessor = processor!!
                )
                launchMonitoringServer()
            }

            // Запуск всех клиентов
            launchExchangeClients()

            // Основной цикл мониторинга
            runMonitoringLoop()

        } catch (e: Exception) {
            log.error(e) { "Ошибка при запуске сервиса" }
            stop()
        }
    }

    private fun launchMonitoringServer() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                monitoringServer?.start()
            } catch (e: Exception) {
                log.error(e) { "Ошибка при запуске сервера мониторинга" }
            }
        }
    }

    private fun launchExchangeClients() {
        exchangeClients.forEach { client ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    client.start()
                } catch (e: Exception) {
                    log.error(e) { "Ошибка при запуске клиента биржи" }
                }
            }
        }
    }

    private suspend fun runMonitoringLoop() {
        while (isRunning.get()) {
            delay(5000)
            logStatus()
        }
    }

    private fun logStatus() {
        val metrics = processor?.getMetrics() ?: emptyMap()
        log.info {
            """
            === Статус сервиса ===
            Клиентов: ${exchangeClients.size}
            Всего тиков: ${metrics["totalTrades"] ?: 0}
            TPS: ${metrics["tradesPerSecond"] ?: 0}
            Память: ${"%.1f".format(Runtime.getRuntime().totalMemory() / 1024.0 / 1024.0)} MB
            =====================
            """.trimIndent()
        }
    }

    suspend fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        log.info { "Остановка TradeCollectorService..." }

        // Остановка мониторинга
        monitoringServer?.stop()

        // Остановка всех клиентов
        exchangeClients.forEach { client ->
            try {
                client.stop()
            } catch (e: Exception) {
                log.error(e) { "Ошибка при остановке клиента" }
            }
        }

        exchangeClients.clear()
        processor = null

        log.info { "Сервис остановлен" }
    }

    fun isServiceRunning(): Boolean = isRunning.get()

    fun getServiceMetrics(): Map<String, Any> {
        val baseMetrics = processor?.getMetrics() ?: emptyMap()

        return baseMetrics + mapOf(
            "exchangeClients" to exchangeClients.size,
            "isRunning" to isRunning.get(),
            "memoryUsedMB" to Runtime.getRuntime().totalMemory() / 1024 / 1024,
            "memoryFreeMB" to Runtime.getRuntime().freeMemory() / 1024 / 1024
        )
    }
}