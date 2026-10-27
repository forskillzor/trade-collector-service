package com.aandios.telemetry

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class AdminServer(
    private val port: Int,
    private val host: String,
    private val metrics: ServiceMetrics,
    private val serviceName: String,
    private val version: String,
    private val readiness: () -> Boolean
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        server = embeddedServer(CIO, port = port, host = host) {
            configureRoutes()
        }
        server?.start(wait = false)
        log.info { "Admin server started on $host:$port" }
    }

    private fun Application.configureRoutes() {
        routing {
            get("/health") {
                call.respondText(
                    """{"status":"ok","service":"$serviceName","version":"$version"}""",
                    ContentType.Application.Json
                )
            }

            get("/ready") {
                val ready = runCatching { readiness() }.getOrDefault(false)
                val status = if (ready) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
                val body = if (ready) {
                    """{"status":"ready","service":"$serviceName"}"""
                } else {
                    """{"status":"degraded","service":"$serviceName"}"""
                }
                call.respondText(body, ContentType.Application.Json, status)
            }

            get("/metrics") {
                call.respondText(
                    metrics.scrape(),
                    ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
                )
            }
        }
    }

    fun stop() {
        server?.stop(1000, 5000)
        server = null
        log.info { "Admin server stopped" }
    }
}
