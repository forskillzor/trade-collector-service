package com.aandios

import com.aandios.config.ConfigManager
import com.aandios.service.TradeCollectorService
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    // Настройка обработки исключений
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Необработанное исключение в корутине" }
        exitProcess(1)
    }

    // Загрузка конфигурации
    ConfigManager.loadFromFile()
    val config = ConfigManager.getConfig()

    log.info { "=".repeat(50) }
    log.info { "Запуск TradeCollectorService v1.0" }
    log.info { "Конфигурация: ${config.exchanges.size} бирж, ${config.exchanges.flatMap { it.symbols }.size} символов" }
    log.info { "Мониторинг: http://localhost:${config.monitoring.httpPort}" }
    log.info { "=".repeat(50) }

    // Создание и запуск сервиса
    val service = TradeCollectorService()

    // Обработка сигналов завершения
    val shutdownHandler = Thread {
        log.info { "\nПолучен сигнал завершения..." }
        runBlocking {
            service.stop()
        }
        log.info { "Сервис завершен" }
        exitProcess(0)
    }

    Runtime.getRuntime().addShutdownHook(shutdownHandler)

    try {
        // Запуск сервиса
        service.start()

        // Бесконечное ожидание (можно добавить управление через консоль)
        while (service.isServiceRunning()) {
            delay(1000)
        }

    } catch (e: Throwable) {
        log.error(e) { "Критическая ошибка в приложении" }
        service.stop()
        exitProcess(1)
    }
}