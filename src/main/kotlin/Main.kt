package com.aandios

import com.aandios.config.ConfigManager
import com.aandios.service.ShutdownChain
import com.aandios.service.TradeCollectorService
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlin.system.exitProcess
import java.io.File

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    log.info { "DEBUG | cwd=${File(".").absolutePath} config_exists=${File("config.json").exists()}" }

    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Unhandled exception" }
        exitProcess(1)
    }

    val scope = CoroutineScope(Dispatchers.Default + exceptionHandler)

    try {

        log.info { "Loading config..." }
        val configLoaded = ConfigManager.loadFromEnv()

        if (!configLoaded) {
            log.error { "Config not found, expected at: ${File(".").absolutePath}/config.json" }
            exitProcess(1)
        }

        val config = ConfigManager.getConfig()

        log.info { "Exchanges loaded: ${config.exchanges.size}" }
        config.exchanges.forEach {
            log.info { "  ${it.name}: ${it.symbols.size} pairs (enabled=${it.enabled})" }
        }

        // Проверяем что это PostgreSQL
        if (config.database.type.lowercase() != "postgresql") {
            log.error { "Unsupported DB type, use type='postgresql' in config.json" }
            exitProcess(1)
        }

        log.info { "TradeCollectorService v${BuildConfig.VERSION} | exchanges=${config.exchanges.size} | monitor=:${config.monitoring.httpPort} | db=${config.database.host}:${config.database.port}/${config.database.database} | filter=${config.processor.filterPercentile} | tfs=${config.processor.timeframes}" }

        // Инициализация БД
        log.info { "Connecting to PostgreSQL at ${config.database.host}:${config.database.port}/${config.database.database}..." }
        val hikariDataSource = TradeDAO.createDataSource(config.database)
        val dao = TradeDAO(hikariDataSource)

        // Создание сервиса
        val service = TradeCollectorService(dao, config)

        // Обработка сигналов завершения
        val shutdownHook = Thread {
            log.info { "Shutdown signal received" }
            runBlocking {
                ShutdownChain()
                    .step("Service", 60_000) { service.stop() }
                    .step("DAO", 15_000) { dao.shutdown() }
                    .execute()
            }
            log.info { "Service finished" }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Запуск сервиса
        service.start()

        // Ожидание завершения
        while (service.isRunning()) {
            delay(1000)
        }

    } catch (e: Throwable) {
        log.error(e) { "Fatal startup error" }
        exitProcess(1)
    }
}

object BuildConfig {
    const val VERSION = "2.0.0"
}