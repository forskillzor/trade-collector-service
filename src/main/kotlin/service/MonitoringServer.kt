package com.aandios.service

import com.aandios.BuildConfig
import com.aandios.config.ConfigManager
import com.aandios.storage.postgres.TradeDAO
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.ContentType
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
private val jmapper = jacksonObjectMapper()

class MonitoringServer(
    private val port: Int = 8080,
    private val host: String = "0.0.0.0",
    private val tradeProcessor: TradeProcessor,
    private val tradeDAO: TradeDAO,
    private val exchangeClients: List<ExchangeClient> = emptyList(),
) {
    private var server: EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration>? = null

    //    private var server: JettyApplicationEngine? = null
    private var serverJob: Job? = null

    fun start() {
        serverJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Создаем сервер
                val engine = embeddedServer(Jetty, port = port, host = host) {
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
                            val dbHealthy = tradeDAO.ping()
                            val wsConnected = exchangeClients.filter { it.isConnected() }.map { it.getName() }
                            val wsTotal = exchangeClients.size

                            val healthy = dbHealthy && wsConnected.isNotEmpty()

                            val status = mutableMapOf(
                                "status" to if (healthy) "healthy" else "degraded",
                                "service" to "TradeCollectorService",
                                "version" to BuildConfig.VERSION,
                                "checks" to mapOf(
                                    "database" to if (dbHealthy) "ok" else "down",
                                    "websockets" to mapOf(
                                        "total" to wsTotal,
                                        "connected" to wsConnected.size,
                                        "exchanges" to wsConnected
                                    )
                                )
                            )
                            call.respondText(jmapper.writeValueAsString(status), ContentType.Application.Json)
                        }

                        get("/metrics") {
                            val metrics = tradeProcessor.getMetrics()
                            call.respondText(jmapper.writeValueAsString(metrics), ContentType.Application.Json)
                        }

                        get("/status") {
                            val config = ConfigManager.getConfig()
                            val metrics = tradeProcessor.getMetrics()

                            val status = mapOf(
                                "service" to "TradeCollectorService",
                                "version" to BuildConfig.VERSION,
                                "metrics" to metrics
                            )
                            call.respondText(jmapper.writeValueAsString(status), ContentType.Application.Json)
                        }

                        get("/exchanges") {
                            val config = ConfigManager.getConfig()
                            call.respond(config.exchanges)
                        }
                        get("/database/stats") {
                            val stats = tradeDAO.getDatabaseStats()
                            val data = mapOf(
                                "database" to "PostgreSQL",
                                "stats" to stats,
                                "timestamp" to System.currentTimeMillis()
                            )
                            call.respondText(jmapper.writeValueAsString(data), ContentType.Application.Json)
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