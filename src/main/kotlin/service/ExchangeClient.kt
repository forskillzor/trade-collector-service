package com.aandios.service

import com.aandios.config.ExchangeConfig
import com.aandios.exchange.ExchangeAdapterFactory
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class ExchangeClient(
    private val config: ExchangeConfig,
    private val processor: TradeProcessor
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
        val url = adapter.getCombinedStreamUrl(config.symbols)

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
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
        var frameCount = 0

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}: combined connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}: combined WebSocket connected (${config.symbols.size} pairs)" }
                    reconnectAttempts = 0
                    frameCount = 0

                    // Diagnostic: warn if no frames after 10 seconds
                    val diagJob = launch {
                        delay(10_000)
                        if (frameCount == 0) log.warn { "${config.name}: NO frames after 10s — WebSocket might be silent" }
                    }

                    for (frame in incoming) {
                        diagJob.cancel()
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

                                if (!adapter.isTradeMessageNode(node)) {
                                    if (frameCount <= 5) log.debug { "${config.name}: NON-TRADE #$frameCount $symbol" }
                                    continue
                                }

                                if (frameCount <= 5) log.debug { "${config.name}: TRADE #$frameCount $symbol" }

                                processor.process(node.toString(), config.name, symbol)
                            }
                            is Frame.Binary -> {
                                if (frameCount <= 5) log.debug { "${config.name}: BINARY frame" }
                            }
                            is Frame.Ping -> {
                                if (frameCount <= 5) log.debug { "${config.name}: PING frame" }
                            }
                            is Frame.Pong -> {
                                if (frameCount <= 5) log.debug { "${config.name}: PONG frame" }
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()?.message ?: "no reason"
                                log.info { "${config.name}: combined connection closed ($reason)" }
                                break
                            }
                            else -> {
                                log.debug { "${config.name}: unknown frame type: ${frame::class.simpleName}" }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                val delayMs = calculateReconnectDelay(reconnectAttempts, maxReconnectDelay)
                log.warn { "${config.name}: combined error, reconnecting in ${delayMs / 1000}s" }
                delay(delayMs)
            }
        }
    }

    private suspend fun launchClientForSymbol(symbol: String) {
        val url = adapter.getWebSocketUrl(symbol)

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
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
        var frameCount = 0
        val silenceTimeoutMs = 60_000L

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}/$symbol: connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}/$symbol: WebSocket connected" }
                    reconnectAttempts = 0
                    frameCount = 0

                    // Silence watchdog: if no frames for silenceTimeoutMs, force reconnect
                    val watchdog = launch {
                        delay(silenceTimeoutMs)
                        if (frameCount == 0) {
                            log.warn { "${config.name}/$symbol: NO frames after ${silenceTimeoutMs / 1000}s — forcing reconnect" }
                            cancel("Silence timeout")
                        }
                    }

                    // Отправляем подписку если требуется
                    adapter.getSubscribeMessage(symbol)?.let { message ->
                        send(message)
                        log.debug { "${config.name}/$symbol: subscribed" }
                    }

                    for (frame in incoming) {
                        watchdog.cancel()
                        when (frame) {
                            is Frame.Text -> {
                                frameCount++
                                val text = frame.readText()

                                // Фильтруем служебные сообщения
                                if (!adapter.isTradeMessage(text)) {
                                    continue
                                }

                                processor.process(text, config.name, symbol)
                            }
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