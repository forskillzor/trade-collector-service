package com.aandios

import com.aandios.config.ConfigManager
import com.aandios.service.TradeCollectorService
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import kotlinx.coroutines.*
import kotlin.system.exitProcess
import java.nio.file.Paths
import java.io.File

private val log = KotlinLogging.logger {}

fun main() = runBlocking {
    log.info { "=== ОТЛАДКА ПУТЕЙ ===" }
    log.info { "1. System.getProperty(\"user.dir\"): ${System.getProperty("user.dir")}" }
    log.info { "2. File(\".\").absolutePath: ${File(".").absolutePath}" }
    log.info { "3. File(\"config.json\").absolutePath: ${File("config.json").absolutePath}" }
    log.info { "4. File(\"config.json\").exists(): ${File("config.json").exists()}" }

    // Посмотрим, что есть в текущей директории
    val currentDir = File(".")
    log.info { "5. Содержимое текущей директории:" }
    currentDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
        log.info { "   - ${file.name} (${if (file.isFile) "файл" else "папка"}, ${file.length()} байт)" }
    }
    log.info { "=========================" }

    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Необработанное исключение" }
        exitProcess(1)
    }

    val scope = CoroutineScope(Dispatchers.Default + exceptionHandler)

    try {

        log.info { "🔍 Загрузка конфигурации..." }
        val configLoaded = ConfigManager.loadFromFile()

        val configPath = "config.json"

        if (!configLoaded) {
            log.error { "❌ Конфигурация не загружена. Создайте файл config.json" }
            log.error { "📁 Разместите его в: ${File(".").absolutePath}" }
            exitProcess(1)  // ✅ Завершаем программу
        }

        // Загрузка конфигурации
        ConfigManager.loadFromFile(configPath)

        val config = ConfigManager.getConfig()

        log.info { "📊 Загружено бирж: ${config.exchanges.size}" }
        config.exchanges.forEach {
            log.info { "   • ${it.name}: ${it.symbols.size} пар, enabled=${it.enabled}" }
        }

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