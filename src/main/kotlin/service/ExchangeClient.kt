package com.aandios.service

import com.aandios.config.ExchangeConfig
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

class ExchangeClient(
    private val config: ExchangeConfig,
    private val processor: TradeProcessor
) {
    private val clients = mutableMapOf<String, HttpClient>()

    suspend fun start() {
        log.info { "Запуск клиента для ${config.name}" }

        config.symbols.forEach { symbol ->
            launchClientForSymbol(symbol)
        }
    }

    private suspend fun launchClientForSymbol(symbol: String) {
        val url = when (config.name) {
            "Binance" -> "${config.baseUrl}/${symbol.lowercase()}@trade"
            "Bybit" -> config.baseUrl
            else -> throw IllegalArgumentException("Unknown exchange: ${config.name}")
        }

        val client = HttpClient {
            install(WebSockets) {
                maxFrameSize = Long.MAX_VALUE
                pingInterval = 20.seconds
            }
        }

        clients[symbol] = client

        CoroutineScope(Dispatchers.IO).launch {
            connectAndListen(url, symbol, client)
        }
    }

    private suspend fun connectAndListen(url: String, symbol: String, client: HttpClient) {
        var reconnectAttempts = 0

        while (true) {
            try {
                reconnectAttempts++
                log.info { "${config.name}/$symbol: Попытка подключения #$reconnectAttempts" }

                client.webSocket(url) {
                    log.info { "${config.name}/$symbol: WebSocket соединение установлено" }

                    // Для Bybit нужно отправить подписку
                    if (config.name == "Bybit") {
                        val subscribeMessage = """
                            {
                                "op": "subscribe",
                                "args": ["publicTrade.$symbol"]
                            }
                        """.trimIndent()
                        send(subscribeMessage)
                        log.info { "${config.name}/$symbol: Отправлена подписка" }
                    }

                    reconnectAttempts = 0

                    for (frame in incoming) {
                        when (frame) {
                            is Frame.Text -> {
                                val text = frame.readText()
                                processor.process(text, config.name, symbol)
                            }
                            is Frame.Close -> {
                                log.info { "${config.name}/$symbol: WebSocket закрыт" }
                                break
                            }
                            else -> {}
                        }
                    }
                }
            } catch (e: Exception) {
                val delayMs = calculateReconnectDelay(reconnectAttempts)
                log.error(e) {
                    "${config.name}/$symbol: Ошибка соединения. Переподключение через ${delayMs/1000}сек"
                }
                delay(delayMs)
            }
        }
    }

    private fun calculateReconnectDelay(attempt: Int): Long {
        val delay = 1000L * (1 shl (attempt - 1).coerceAtMost(5))
        return delay.coerceAtMost(30000L)
    }

    suspend fun stop() {
        clients.values.forEach { it.close() }
        clients.clear()
    }
}