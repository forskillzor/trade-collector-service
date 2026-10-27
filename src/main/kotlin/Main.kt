/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios

import com.aandios.config.AppConfig
import com.aandios.config.ConfigManager
import com.aandios.service.ShutdownChain
import com.aandios.service.TradeCollectorService
import com.aandios.storage.postgres.TradeDAO
import com.aandios.telemetry.ServiceMetrics
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.File
import kotlin.system.exitProcess

private val log = KotlinLogging.logger {}

fun main() {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        log.error(throwable) { "Unhandled coroutine exception" }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)

    try {
        val config = loadConfig()
        logExchanges(config)

        val dao = createDao(config)
        val service = TradeCollectorService(dao, config, scope, ServiceMetrics())

        val shutdownHook = Thread {
            log.info { "Shutdown signal received" }
            try {
                runBlocking {
                    ShutdownChain()
                        .step("Service", 60_000) { service.stop() }
                        .step("DAO", 15_000) { dao.shutdown() }
                        .execute()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                log.warn { "Shutdown interrupted" }
            } catch (e: Throwable) {
                log.error(e) { "Shutdown failed" }
            }
            log.info { "Service finished" }
        }
        Runtime.getRuntime().addShutdownHook(shutdownHook)

        runBlocking {
            service.start()
            service.awaitTermination()
        }
    } catch (e: Throwable) {
        log.error(e) { "Fatal startup error" }
        exitProcess(1)
    } finally {
        scope.cancel()
    }
}

private fun loadConfig(): AppConfig {
    log.info { "Loading config..." }
    if (!ConfigManager.loadFromEnv()) {
        error("Config not found, expected at: ${File(".").absolutePath}/config.json")
    }

    val config = ConfigManager.getConfig()
    require(config.database.type.equals("postgresql", ignoreCase = true)) {
        "Unsupported DB type, use type='postgresql' in config.json"
    }

    log.info {
        "TradeCollectorService v${BuildConfig.VERSION} | exchanges=${config.exchanges.size} | " +
            "admin=:${config.monitoring.httpPort} | " +
            "db=${config.database.host}:${config.database.port}/${config.database.database} | " +
            "whaleFilter=${config.processor.whalePercentile} | tfs=${config.processor.timeframes}"
    }
    return config
}

private fun logExchanges(config: AppConfig) {
    log.info { "Exchanges loaded: ${config.exchanges.size}" }
    config.exchanges.forEach {
        log.info { "  ${it.name}: ${it.symbols.size} pairs (enabled=${it.enabled})" }
    }
}

private fun createDao(config: AppConfig): TradeDAO {
    log.info {
        "Connecting to PostgreSQL at " +
            "${config.database.host}:${config.database.port}/${config.database.database}..."
    }
    return TradeDAO(TradeDAO.createDataSource(config.database))
}

object BuildConfig {
    const val VERSION = "2.0.0"
}
