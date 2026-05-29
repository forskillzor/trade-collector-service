package com.aandios.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File

private val log = KotlinLogging.logger {}

@Serializable
data class ExchangeConfig(
    val name: String,
    val symbols: List<String>,
    val enabled: Boolean = true
)

@Serializable
data class DatabaseConfig(
    val type: String = "postgresql",
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String = "trade_collector",
    val username: String = "trade_user",
    val password: String? = null,
    val batchSize: Int = 1000,
    val flushIntervalMs: Long = 1000
) {
    // Вычисляемые свойства для переменных окружения
    val resolvedHost: String
        get() = System.getenv("DB_HOST") ?: host

    val resolvedPort: Int
        get() = System.getenv("DB_PORT")?.toIntOrNull() ?: port

    val resolvedDatabase: String
        get() = System.getenv("DB_NAME") ?: database

    val resolvedUsername: String
        get() = System.getenv("DB_USER") ?: username

    val resolvedPassword: String
        get() = System.getenv("DB_PASSWORD") ?: password
        ?: throw IllegalStateException(
            "Database password not configured! Set DB_PASSWORD environment variable or in config.json"
        )

    // Для логирования (без пароля)
    fun getConnectionStringSafe(): String {
        return "postgresql://${resolvedUsername}@${resolvedHost}:${resolvedPort}/${resolvedDatabase}"
    }
}
@Serializable
data class ProcessorConfig(
    val batchSize: Int = 1000,
    val flushIntervalMs: Long = 1000,
    val windowSize: Int = 1000000,
    val slideStep: Int = 100000,
    val filterPercentile: Double = 0.98,
    val timeframes: List<String> = listOf("1m", "5m", "1h"),
    val aggregatesOutputDir: String = "./aggregates"
)

@Serializable
data class ExportConfig(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 60,
    val outputDir: String = "./exports",
    val keepDays: Int = 7
)

@Serializable
data class MonitoringConfig(
    val httpPort: Int = 8080,
    val enableMetrics: Boolean = true,
    val metricsPath: String = "/metrics",
    val healthPath: String = "/health",
    val statusPath: String = "/status"
)

@Serializable
data class AppConfig(
    val exchanges: List<ExchangeConfig> = emptyList(),
    val database: DatabaseConfig = DatabaseConfig(),
    val processor: ProcessorConfig = ProcessorConfig(),
    val export: ExportConfig = ExportConfig(),
    val monitoring: MonitoringConfig = MonitoringConfig()
)

object ConfigManager {
    private var config: AppConfig = AppConfig()
    private val jsonFormat = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun loadFromFile(path: String = "config.json"): Boolean {
        return try {
            // ✅ Пробуем несколько путей
            val possiblePaths = listOf(
                path,  // Текущая директория
                "../$path",  // На уровень выше (для запуска из подпапки)
                "../../$path", // На два уровня выше
                "src/main/resources/$path", // В ресурсах
                System.getProperty("user.dir") + "/$path", // Абсолютный путь из рабочей директории
                File(".").absolutePath + "/$path" // Текущая директория как File
            )

            var configFile: File? = null
            var foundPath: String? = null

            for (possiblePath in possiblePaths) {
                val file = File(possiblePath)
                if (file.exists() && file.isFile) {
                    configFile = file
                    foundPath = file.absolutePath
                    break
                }
            }

            if (configFile == null) {
                log.error { "❌ Файл конфигурации не найден по путям:" }
                possiblePaths.forEach { log.error { "   • $it" } }
                log.error { "📁 Текущая рабочая директория: ${System.getProperty("user.dir")}" }
                log.error { "📁 Абсолютный путь .: ${File(".").absolutePath}" }
                return false
            }

            log.info { "✅ Найден config.json: $foundPath" }
            val json = configFile.readText()

            config = jsonFormat.decodeFromString<AppConfig>(json)

            log.info { "✅ Конфигурация успешно загружена" }
            true

        } catch (e: Exception) {
            log.error(e) { "❌ Ошибка парсинга конфигурации" }
            false
        }
    }

    private fun createDefaultConfig(): AppConfig {
        return AppConfig(
            exchanges = listOf(
                ExchangeConfig(
                    name = "Binance",
                    symbols = listOf("btcusdt", "ethusdt", "solusdt"),
                    enabled = true
                ),
                ExchangeConfig(
                    name = "Bybit",
                    symbols = listOf("BTCUSDT", "ETHUSDT", "SOLUSDT"),
                    enabled = true
                )
            ),
            database = DatabaseConfig(
                type = "postgresql",
                host = "localhost",
                port = 6432,
                database = "trade_collector",
                username = "trade_user",
                password = "your_password_here",
                batchSize = 1000,
                flushIntervalMs = 1000
            ),
            processor = ProcessorConfig(),
            export = ExportConfig(),
            monitoring = MonitoringConfig()
        )
    }

    fun getConfig(): AppConfig = config
}