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

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}: combined connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}: combined WebSocket connected (${config.symbols.size} pairs)" }
                    reconnectAttempts = 0

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                val parsed = adapter.parseCombinedFrame(text) ?: continue
                                val (symbol, data) = parsed

                                if (!adapter.isTradeMessage(data)) continue

                                processor.process(data, config.name, symbol)
                            }
                            is Frame.Close -> {
                                val reason = frame.readReason()?.message ?: "no reason"
                                log.info { "${config.name}: combined connection closed ($reason)" }
                                break
                            }
                            else -> {}
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

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}/$symbol: connect attempt #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}/$symbol: WebSocket connected" }

                    // Отправляем подписку если требуется
                    adapter.getSubscribeMessage(symbol)?.let { message ->
                        send(message)
                        log.debug { "${config.name}/$symbol: subscribed" }
                    }

                    reconnectAttempts = 0

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
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
                }
            } catch (e: Exception) {
                val delayMs = calculateReconnectDelay(reconnectAttempts, maxReconnectDelay)
                log.warn { "${config.name}/$symbol: error, reconnecting in ${delayMs / 1000}s" }
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