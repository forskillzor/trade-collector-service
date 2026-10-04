package com.aandios.service

import com.aandios.config.ExchangeConfig
import com.aandios.exchange.ExchangeAdapterFactory
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

class ExchangeClient(
    private val config: ExchangeConfig,
    private val processor: TradeProcessor,
    private val liquidationProcessor: LiquidationProcessor? = null
) {
    private val adapter = ExchangeAdapterFactory.createAdapter(config.name)
    private val clients = mutableMapOf<String, HttpClient>()
    private var clientScope: CoroutineScope? = null

    suspend fun start() {
        clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        log.info { "Client ${config.name}: starting (${config.symbols.size} pairs)" }

        if (adapter.supportsCombinedStream()) {
            launchCombinedStream()
        } else {
            config.symbols.forEach { symbol ->
                launchClientForSymbol(symbol)
            }
        }
    }

    private suspend fun launchCombinedStream() {
        val url = if (config.collectLiquidations) {
            adapter.getCombinedStreamUrlWithLiq(config.symbols, true)
        } else {
            adapter.getCombinedStreamUrl(config.symbols)
        }

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                pingInterval = 30.seconds
            }
        }

        clients["__combined__"] = client

        clientScope?.let { scope ->
            scope.launch {
                connectAndListenCombined(url, client)
            }
        }
    }

    private suspend fun connectAndListenCombined(url: String, client: HttpClient) {
        var reconnectAttempts = 0
        val maxReconnectDelay = 30000L
        val silenceTimeoutMs = 120_000L
        var frameCount = 0

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}: combined connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}: combined WebSocket connected (${config.symbols.size} pairs)" }
                    reconnectAttempts = 0
                    frameCount = 0

                    var lastFrameTime = System.currentTimeMillis()

                    // Watchdog: force reconnect if no frames for silenceTimeoutMs
                    val watchdog = launch {
                        while (isActive) {
                            delay(10_000)
                            if (System.currentTimeMillis() - lastFrameTime > silenceTimeoutMs) {
                                log.warn { "${config.name}: combined NO frames for ${silenceTimeoutMs / 1000}s — forcing reconnect" }
                                this@webSocket.cancel("Combined stream silence timeout")
                            }
                        }
                    }

                    for (frame in incoming) {
                        lastFrameTime = System.currentTimeMillis()
                        when (frame) {
                            is Frame.Text -> {
                                frameCount++
                                val text = frame.readText()
                                if (frameCount <= 5) log.debug { "${config.name}: TEXT #$frameCount len=${text.length}" }
                                val parsed = adapter.parseCombinedFrame(text)
                                if (parsed == null) {
                                    if (frameCount <= 5) log.debug { "${config.name}: UNPARSED #$frameCount" }
                                    continue
                                }
                                val (symbol, node) = parsed

                                if (adapter.isLiquidationMessageNode(node)) {
                                    val liquidation = adapter.parseLiquidationNode(node, symbol)
                                    if (liquidation != null) {
                                        if (frameCount <= 5) log.debug { "${config.name}: LIQUIDATION #$frameCount $symbol" }
                                        liquidationProcessor?.process(liquidation)
                                    }
                                    continue
                                }

                                if (!adapter.isTradeMessageNode(node)) {
                                    if (frameCount <= 5) log.debug { "${config.name}: NON-TRADE #$frameCount $symbol" }
                                    continue
                                }

                                if (frameCount <= 5) log.debug { "${config.name}: TRADE #$frameCount $symbol" }

                                processor.process(node.toString(), config.name, symbol)
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()?.message ?: "no reason"
                                log.info { "${config.name}: combined connection closed ($reason)" }
                                break
                            }
                            else -> {}
                        }
                    }
                    watchdog.cancel()
                }
            } catch (e: Exception) {
                val delayMs = calculateReconnectDelay(reconnectAttempts, maxReconnectDelay)
                log.warn { "${config.name}: combined error (${e.message}), reconnecting in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    private suspend fun launchClientForSymbol(symbol: String) {
        val url = adapter.getWebSocketUrl(symbol)

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                pingInterval = 30.seconds
            }
        }

        clients[symbol] = client

        clientScope?.let { scope ->
            scope.launch {
                connectAndListen(url, symbol, client)
            }
        }
    }

    private suspend fun connectAndListen(url: String, symbol: String, client: HttpClient) {
        var reconnectAttempts = 0
        val maxReconnectDelay = 30000L
        val silenceTimeoutMs = 60_000L

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}/$symbol: connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}/$symbol: WebSocket connected" }
                    reconnectAttempts = 0

                    var lastTradeTime = System.currentTimeMillis()

                    // Watchdog: if no trades for silenceTimeoutMs, force reconnect
                    // Runs in a loop, checks every 10s
                    val watchdog = launch {
                        while (isActive) {
                            delay(10_000)
                            if (System.currentTimeMillis() - lastTradeTime > silenceTimeoutMs) {
                                log.warn { "${config.name}/$symbol: NO trades for ${silenceTimeoutMs / 1000}s — forcing reconnect" }
                                this@webSocket.cancel("Trade silence timeout")
                            }
                        }
                    }

                    adapter.getSubscribeMessage(symbol)?.let { message ->
                        send(message)
                        log.debug { "${config.name}/$symbol: subscribed" }
                    }

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                if (!adapter.isTradeMessage(text)) {
                                    continue
                                }
                                lastTradeTime = System.currentTimeMillis()
                                processor.process(text, config.name, symbol)
                            }
                            is Frame.Ping -> { lastTradeTime = System.currentTimeMillis() }
                            is Frame.Pong -> { lastTradeTime = System.currentTimeMillis() }
                            is Frame.Close -> {
                                val reason = frame.readReason()?.message ?: "no reason"
                                log.info { "${config.name}/$symbol: connection closed ($reason)" }
                                break
                            }
                            else -> {}
                        }
                    }
                    watchdog.cancel()
                }
            } catch (e: Exception) {
                val delayMs = calculateReconnectDelay(reconnectAttempts, maxReconnectDelay)
                log.warn { "${config.name}/$symbol: error (${e.message}), reconnecting in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    private fun calculateReconnectDelay(attempt: Int, maxDelay: Long): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        val delay = 1000L * (1L shl shift)
        return delay.coerceAtMost(maxDelay)
    }

    suspend fun stop() {
        clientScope?.cancel()
        clientScope = null

        clients.values.forEach { it.close() }
        clients.clear()

        log.info { "Client ${config.name} stopped" }
    }

    fun isConnected(): Boolean = clientScope != null && clientScope!!.isActive

    fun getName(): String = config.name
}