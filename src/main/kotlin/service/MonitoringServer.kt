package com.aandios.service

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val log = KotlinLogging.logger {}
private val json = Json { prettyPrint = true }

class MonitoringServer(
    private val port: Int = 8080,
    private val tradeProcessor: TradeProcessor
) {
    private var server: ApplicationEngine? = null

    suspend fun start() {
        server = embeddedServer(Netty, port = port) {
            routing {
                get("/") {
                    call.respondText("""
                        TradeCollectorService Monitoring
                        Endpoints:
                        - /health - Health check
                        - /metrics - Service metrics
                        - /status - Detailed status
                    """.trimIndent())
                }

                get("/health") {
                    call.respond(mapOf("status" to "OK", "timestamp" to System.currentTimeMillis()))
                }

                get("/metrics") {
                    val metrics = tradeProcessor.getMetrics()
                    call.respond(metrics)
                }

                get("/status") {
                    val status = mapOf(
                        "service" to "TradeCollectorService",
                        "timestamp" to System.currentTimeMillis(),
                        "metrics" to tradeProcessor.getMetrics(),
                        "memory" to mapOf(
                            "total" to Runtime.getRuntime().totalMemory(),
                            "free" to Runtime.getRuntime().freeMemory(),
                            "max" to Runtime.getRuntime().maxMemory()
                        )
                    )
                    call.respondText(json.encodeToString(status))
                }
            }
        }.start(wait = false)

        log.info { "Сервер мониторинга запущен на порту $port" }
    }

    fun stop() {
        server?.stop(1000, 5000)
        log.info { "Сервер мониторинга остановлен" }
    }
}