package com.aandios.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ExchangeConfig(
    val name: String,
    val baseUrl: String,
    val symbols: List<String>,
    val enabled: Boolean = true
)

@Serializable
data class AppConfig(
    val exchanges: List<ExchangeConfig> = listOf(
        ExchangeConfig(
            name = "Binance",
            baseUrl = "wss://stream.binance.com:9443/ws",
            symbols = listOf("btcusdt", "ethusdt")
        ),
        ExchangeConfig(
            name = "Bybit",
            baseUrl = "wss://stream.bybit.com/v5/public/spot",
            symbols = listOf("BTCUSDT", "ETHUSDT")
        )
    ),
    val database: DatabaseConfig = DatabaseConfig(),
    val export: ExportConfig = ExportConfig(),
    val monitoring: MonitoringConfig = MonitoringConfig()
)

@Serializable
data class DatabaseConfig(
    val type: String = "sqlite",
    val path: String = "trades.db",
    val batchSize: Int = 100,
    val flushIntervalMs: Long = 1000
)

@Serializable
data class ExportConfig(
    val enabled: Boolean = true,
    val intervalMinutes: Int = 60,
    val outputDir: String = "./exports"
)

@Serializable
data class MonitoringConfig(
    val httpPort: Int = 8080,
    val enableMetrics: Boolean = true
)

object ConfigManager {
    private var config: AppConfig = AppConfig()

    fun loadFromFile(path: String = "config.json") {
        try {
            val configFile = File(path)
            if (configFile.exists()) {
                val json = configFile.readText()
                config = Json { ignoreUnknownKeys = true }.decodeFromString<AppConfig>(json)
                println("Конфигурация загружена из $path")
            } else {
                saveToFile(path)
                println("Создан файл конфигурации по умолчанию: $path")
            }
        } catch (e: Exception) {
            println("Ошибка загрузки конфигурации: ${e.message}. Используются значения по умолчанию.")
        }
    }

    fun saveToFile(path: String = "config.json") {
        try {
            val json = Json { prettyPrint = true }.encodeToString(config)
            File(path).writeText(json)
        } catch (e: Exception) {
            println("Ошибка сохранения конфигурации: ${e.message}")
        }
    }

    fun getConfig(): AppConfig = config
}