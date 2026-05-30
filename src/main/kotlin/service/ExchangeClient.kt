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
    private val processor: TradeProcessor
) {
    private val adapter = ExchangeAdapterFactory.createAdapter(config.name)
    private val clients = mutableMapOf<String, HttpClient>()
    private val clientJobs = mutableListOf<Job>()

    suspend fun start() {
        log.info { "Client ${config.name}: starting (${config.symbols.size} pairs)" }

        config.symbols.forEach { symbol ->
            launchClientForSymbol(symbol)
        }
    }

    private suspend fun launchClientForSymbol(symbol: String) {
        val url = adapter.getWebSocketUrl(symbol)

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                pingInterval = 20.seconds
            }
        }

        clients[symbol] = client

        val job = CoroutineScope(Dispatchers.IO).launch {
            connectAndListen(url, symbol, client)
        }
        clientJobs.add(job)
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
                log.error(e) {
                    "${config.name}/$symbol: error, reconnecting in ${delayMs / 1000}s"
                }
                delay(delayMs)
            }
        }
    }

    private fun calculateReconnectDelay(attempt: Int, maxDelay: Long): Long {
        val delay = 1000L * (1 shl (attempt - 1).coerceAtMost(5))
        return delay.coerceAtMost(maxDelay)
    }

    suspend fun stop() {
        clientJobs.forEach { it.cancel() }
        clientJobs.clear()

        clients.values.forEach { it.close() }
        clients.clear()

        log.info { "Client ${config.name} stopped" }
    }
}