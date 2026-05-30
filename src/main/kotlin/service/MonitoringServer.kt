package com.aandios.service

import com.aandios.BuildConfig
import com.aandios.config.ConfigManager
import com.aandios.storage.postgres.TradeDAO
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val log = KotlinLogging.logger {}
private val json = Json { prettyPrint = true }

class MonitoringServer(
    private val port: Int = 8080,
    private val tradeProcessor: TradeProcessor,
    private val tradeDAO: TradeDAO,
) {
    private var server: EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration>? = null

    //    private var server: JettyApplicationEngine? = null
    private var serverJob: Job? = null

    fun start() {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Создаем сервер
                val engine = embeddedServer(Jetty, port = port, host = "localhost") {
                    install(ContentNegotiation) { json(json) }

                    routing {
                        get("/") {
                            call.respondText(
                                """
                                TradeCollectorService Monitoring
                                ================================
                                
                                Endpoints:
                                * /health     - Health check
                                * /metrics    - Performance metrics
                                * /status     - Service status
                                * /exchanges  - Connected exchanges
                                
                                Version: ${BuildConfig.VERSION}
                                """.trimIndent()
                            )
                        }

                        get("/health") {
                            call.respond(
                                mapOf(
                                    "status" to "healthy",
//                                    "timestamp" to System.currentTimeMillis(),
                                    "service" to "TradeCollectorService",
                                    "version" to BuildConfig.VERSION
                                )
                            )
                        }

                        get("/metrics") {
                            val metrics = tradeProcessor.getMetrics()
                            call.respond(metrics)
                        }

                        get("/status") {
                            val config = ConfigManager.getConfig()
                            val metrics = tradeProcessor.getMetrics()
                            val memory = Runtime.getRuntime()

                            val status = mapOf(
                                "service" to "TradeCollectorService",
                                "version" to BuildConfig.VERSION,
////                                "timestamp" to System.currentTimeMillis(),
//                                "uptime" to "TODO",
                                "metrics" to metrics,
//                                "exchanges" to config.exchanges.map { it.name },
//                                "memory" to mapOf(
//                                    "totalMB" to memory.totalMemory() / 1024 / 1024,
//                                    "freeMB" to memory.freeMemory() / 1024 / 1024,
//                                    "maxMB" to memory.maxMemory() / 1024 / 1024
//                                ),
//                                "threads" to Thread.activeCount()
                            )
                            call.respond(status)
                        }

                        get("/exchanges") {
                            val config = ConfigManager.getConfig()
                            call.respond(config.exchanges)
                        }
                        get("/database/stats") {
                            val stats = tradeDAO.getDatabaseStats()
                            call.respond(
                                mapOf(
                                    "database" to "PostgreSQL",
                                    "stats" to stats,
                                    "timestamp" to System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }

                // Сохраняем ссылку на engine
                server = engine

                // Запускаем сервер
                engine.start(wait = true)

            } catch (e: Exception) {
                log.error(e) { "Monitoring server start error" }
            }
        }

        log.info { "Monitoring server started on port $port" }
    }

    suspend fun stop() {
        serverJob?.cancel()
        serverJob = null

        server?.let {srv ->
            withContext(Dispatchers.IO) {
                srv.stop(1000, 5000)
            }
        }
        server = null
        log.info { "Monitoring server stopped" }
    }
}