package com.aandios.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File

private val log = KotlinLogging.logger {}

@Serializable
data class ExchangeConfig(
    val name: String,
    val symbols: List<String>,
    val enabled: Boolean = true,
    val collectLiquidations: Boolean = false
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
}

@Serializable
data class ProcessorConfig(
    val batchSize: Int = 1000,
    val flushIntervalMs: Long = 1000,
    val whaleWindowSize: Int = 10000,
    val whalePercentile: Double = 0.98,
    val timeframes: List<String> = listOf("1m", "15m")
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
    val host: String = "0.0.0.0",
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
            val possiblePaths = listOf(
                path,
                "../$path",
                "../../$path",
                "src/main/resources/$path",
                System.getProperty("user.dir") + "/$path",
                "config/config.prod.json",
                System.getProperty("user.dir") + "/config/config.prod.json"
            )
            val file = possiblePaths.map { File(it) }.firstOrNull { it.exists() }
            
            if (file != null) {
                log.info { "Config found: ${file.absolutePath}" }
                config = jsonFormat.decodeFromString(file.readText())
                log.info { "Config loaded successfully" }
                true
            } else {
                log.warn { "No config file found in any location" }
                false
            }
        } catch (e: Exception) {
            log.error(e) { "Failed to load config" }
            false
        }
    }

    fun loadFromEnv(): Boolean {
        return loadFromFile(System.getenv("CONFIG_PATH") ?: "config.json")
    }

    fun getConfig(): AppConfig = config
}
