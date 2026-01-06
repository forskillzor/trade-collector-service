package com.aandios.service

import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import mu.KotlinLogging

private val log = KotlinLogging.logger {}

class WebSocketClient(
    private val url: String,
) {
    private val client = HttpClient {
        install(WebSockets)
    }

    suspend fun connectAndListen(onMessage: (json: String) -> Unit) {
        try {
            client.webSocket(url) {
                log.info { "WebSocket соединение установлено: $url" }

                // Основной цикл приема сообщений
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            onMessage(text)
                        }
                        is Frame.Close -> {
                            log.info { "WebSocket закрыт: ${frame.readReason()}" }
                            break
                        }
                        else -> {
                            log.debug { "Получен не текстовый фрейм: ${frame.frameType}" }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.error(e) { "Ошибка в WebSocket соединении" }
            throw e // Пробрасываем исключение выше для переподключения
        } finally {
            log.info { "WebSocket соединение завершено" }
        }
    }

    fun close() {
        client.close()
    }
}