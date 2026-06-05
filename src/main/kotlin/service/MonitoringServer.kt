package com.aandios.service

import com.aandios.BuildConfig
import com.aandios.config.ConfigManager
import com.aandios.storage.postgres.TradeDAO
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.management.OperatingSystemMXBean
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jetty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.lang.management.ManagementFactory

private val log = KotlinLogging.logger {}
private val json = Json { prettyPrint = true }
private val jmapper = jacksonObjectMapper()

private val osMxBean: OperatingSystemMXBean? =
    (ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean)
// Warm up: first call to getSystemCpuLoad always returns -1
private val osBeanWarmed: Boolean by lazy {
    osMxBean?.systemCpuLoad
    true
}

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
                            val metrics = tradeProcessor.getMetrics()
                            val dbStats = tradeDAO.getDatabaseStats()
                            val runtime = Runtime.getRuntime()
                            val osBean = ManagementFactory.getOperatingSystemMXBean()
                            val memoryBean = ManagementFactory.getMemoryMXBean()

                            // systemCpuLoad: first call returns -1, subsequent calls 0.0-1.0
                            osBeanWarmed
                            val cpuLoad = osMxBean?.systemCpuLoad?.let { if (it < 0) null else it }

                            val diskRoot = File("/")
                            val diskTotalMB = diskRoot.totalSpace / 1024 / 1024
                            val diskFreeMB = diskRoot.freeSpace / 1024 / 1024

                            val heap = memoryBean.heapMemoryUsage
                            val nonHeap = memoryBean.nonHeapMemoryUsage

                            val status = mapOf(
                                "service" to "TradeCollectorService",
                                "version" to BuildConfig.VERSION,
                                "uptime" to ManagementFactory.getRuntimeMXBean().uptime,
                                "timestamp" to System.currentTimeMillis(),
                                "metrics" to metrics,
                                "system" to mapOf(
                                    "availableProcessors" to runtime.availableProcessors(),
                                    "cpuLoad" to cpuLoad,
                                    "memory" to mapOf(
                                        "totalMB" to runtime.totalMemory() / 1024 / 1024,
                                        "freeMB" to runtime.freeMemory() / 1024 / 1024,
                                        "maxMB" to runtime.maxMemory() / 1024 / 1024,
                                        "heapUsedMB" to heap.used / 1024 / 1024,
                                        "heapMaxMB" to heap.max / 1024 / 1024,
                                        "nonHeapUsedMB" to nonHeap.used / 1024 / 1024
                                    ),
                                    "disk" to mapOf(
                                        "totalMB" to diskTotalMB,
                                        "freeMB" to diskFreeMB,
                                        "usedPercent" to ((diskTotalMB - diskFreeMB) * 100 / diskTotalMB)
                                    )
                                ),
                                "database" to dbStats
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

                        get("/api/logs") {
                            val lines = LogCapture.getLines()
                            call.respondText(jmapper.writeValueAsString(lines), ContentType.Application.Json)
                        }

                        get("/api/instruments") {
                            val config = ConfigManager.getConfig()
                            val details = tradeProcessor.getInstrumentDetails()
                            val allSymbols = config.exchanges.flatMap { x -> x.symbols.map { it.uppercase() } }
                            val result = allSymbols.map { symbol ->
                                val d = details[symbol] ?: emptyMap<String, Any>()
                                mapOf(
                                    "symbol" to symbol,
                                    "totalTrades" to (d["totalTrades"] ?: 0),
                                    "lastTradeTime" to (d["lastTradeTime"] ?: 0L),
                                    "batchQueueSize" to (d["batchQueueSize"] ?: 0),
                                    "volumeThreshold" to (d["volumeThreshold"] ?: 0),
                                    "windowSize" to (d["windowSize"] ?: 0),
                                    "processedTrades" to (d["processedTrades"] ?: 0)
                                )
                            }
                            call.respondText(jmapper.writeValueAsString(result), ContentType.Application.Json)
                        }

                        get("/api/history/{symbol}") {
                            val symbol = call.parameters["symbol"] ?: ""
                            val minutes = call.request.queryParameters["minutes"]?.toIntOrNull() ?: 60
                            if (symbol.isBlank()) {
                                call.respondText(jmapper.writeValueAsString(mapOf("error" to "symbol required")), ContentType.Application.Json)
                                return@get
                            }
                            val history = tradeDAO.getHistory(symbol, minutes)
                            call.respondText(jmapper.writeValueAsString(history), ContentType.Application.Json)
                        }

                        val staticDir = File("static")
                        if (staticDir.exists()) {
                            staticFiles("/", staticDir)
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