package com.aandios.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

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
    val port: Int = 6432,
    val database: String = "trade_collector",
    val username: String = "trade_user",
    val password: String = "your_password_here",
    val batchSize: Int = 1000,
    val flushIntervalMs: Long = 1000
)

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

    fun loadFromFile(path: String = "config.json") {
        try {
            val configFile = File(path)
            if (configFile.exists()) {
                val json = configFile.readText()
                config = Json {
                    ignoreUnknownKeys = true
                }.decodeFromString<AppConfig>(json)
                println("✅ Конфигурация загружена из $path")
            } else {
                config = createDefaultConfig()
                saveToFile(path)
                println("📝 Создан файл конфигурации по умолчанию: $path")
                println("ℹ️  Отредактируйте его и перезапустите приложение")
                println("⚠️  Укажите пароль PostgreSQL в config.json!")
            }
        } catch (e: Exception) {
            println("❌ Ошибка загрузки конфигурации: ${e.message}")
            config = createDefaultConfig()
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

    fun saveToFile(path: String = "config.json") {
        try {
            File(path).parentFile?.mkdirs()
            val json = Json { prettyPrint = true }.encodeToString(config)
            File(path).writeText(json)
        } catch (e: Exception) {
            println("❌ Ошибка сохранения конфигурации: ${e.message}")
        }
    }

    fun getConfig(): AppConfig = config
}