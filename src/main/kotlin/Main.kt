package com.aandios

import com.aandios.config.ConfigManager
import com.aandios.service.TradeCollectorService
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlin.system.exitProcess
import java.nio.file.Paths

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Необработанное исключение" }
        exitProcess(1)
    }

    val scope = CoroutineScope(Dispatchers.Default + exceptionHandler)

    try {
        // Загрузка конфигурации
        ConfigManager.loadFromFile()
        val config = ConfigManager.getConfig()

        // Проверяем что это PostgreSQL
        if (config.database.type.lowercase() != "postgresql") {
            log.error { "❌ Поддерживается только PostgreSQL. Укажите type='postgresql' в config.json" }
            exitProcess(1)
        }

        // Создаем директории если их нет
        Paths.get(config.processor.aggregatesOutputDir).toFile().mkdirs()
        Paths.get(config.export.outputDir).toFile().mkdirs()

        log.info { "═".repeat(60) }
        log.info { "🚀 Запуск TradeCollectorService v${BuildConfig.VERSION}" }
        log.info { "📊 Биржи: ${config.exchanges.size}" }
        config.exchanges.forEach { exchange ->
            log.info { "   • ${exchange.name}: ${exchange.symbols.size} пар" }
        }
        log.info { "📈 Мониторинг: http://localhost:${config.monitoring.httpPort}" }
        log.info { "💾 База данных: PostgreSQL ${config.database.host}:${config.database.port}/${config.database.database}" }
        log.info { "🎯 Фильтр: ${config.processor.filterPercentile} перцентиль" }
        log.info { "📊 Таймфреймы: ${config.processor.timeframes}" }
        log.info { "═".repeat(60) }

        // Инициализация БД
        log.info { "🔗 Подключение к PostgreSQL..." }
        val hikariDataSource = TradeDAO.createDataSource(config.database)
        val dao = TradeDAO(hikariDataSource)

        // Создание сервиса
        val service = TradeCollectorService(dao, config)

        // Обработка сигналов завершения
        val shutdownHook = Thread {
            log.info { "\n📴 Получен сигнал завершения..." }
            runBlocking {
                service.stop()
                dao.shutdown()
            }
            log.info { "✅ Сервис завершен" }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        // Запуск сервиса
        service.start()

        // Ожидание завершения
        while (service.isRunning()) {
            delay(1000)
        }

    } catch (e: Throwable) {
        log.error(e) { "❌ Критическая ошибка при запуске" }
        exitProcess(1)
    }
}

object BuildConfig {
    const val VERSION = "2.0.0"
}