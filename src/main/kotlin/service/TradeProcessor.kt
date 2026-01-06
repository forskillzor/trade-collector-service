package com.aandios.service

import com.aandios.model.Trade
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import mu.KotlinLogging
import java.time.Instant

private val log = KotlinLogging.logger {}

class TradeProcessor {
    private val mapper = jacksonObjectMapper()

    // Метрики
    private var totalTrades = 0L
    private var tradesPerSecond = 0
    private var lastSecond = Instant.now().epochSecond
    private var lastTotalTrades = 0L

    fun process(json: String, exchange: String, symbol: String) {
        try {
            val trade = when (exchange) {
                "Binance" -> parseBinanceTrade(json, symbol)
                "Bybit" -> parseBybitTrade(json, symbol)
                else -> throw IllegalArgumentException("Unknown exchange: $exchange")
            }

            // Обновление метрик
            totalTrades++
            updateTps()

            // Логирование (с ограничением частоты)
            if (totalTrades % 100 == 0L) {
                log.debug {
                    "Обработано: $totalTrades | TPS: $tradesPerSecond | " +
                            "${exchange}/$symbol: ${trade.price}"
                }
            }

            // TODO: Сохранение в БД
            // TODO: Добавление в batch для вставки

        } catch (e: Exception) {
            log.error(e) { "Ошибка обработки $exchange/$symbol: ${e.message}" }
        }
    }

    private fun parseBinanceTrade(json: String, symbol: String): Trade {
        val node = mapper.readTree(json)
        return Trade(
            exchange = "Binance",
            symbol = symbol.uppercase(),
            timestamp = node["T"].asLong(),
            price = node["p"].asDouble(),
            quantity = node["q"].asDouble(),
            direction = !node["m"].asBoolean() // true = buy, false = sell
        )
    }

    private fun parseBybitTrade(json: String, symbol: String): Trade {
        val node = mapper.readTree(json)

        // Проверка, что это trade data
        if (node.has("topic") && node["topic"].asText().startsWith("publicTrade.")) {
            val data = node["data"][0]
            return Trade(
                exchange = "Bybit",
                symbol = symbol.uppercase(),
                timestamp = data["T"].asLong(),
                price = data["p"].asDouble(),
                quantity = data["v"].asDouble(),
                direction = data["S"].asText() == "Buy"
            )
        }
        throw IllegalArgumentException("Invalid Bybit trade format: ${node}")
    }

    private fun updateTps() {
        val currentSecond = Instant.now().epochSecond
        if (currentSecond != lastSecond) {
            tradesPerSecond = (totalTrades - lastTotalTrades).toInt()
            lastTotalTrades = totalTrades
            lastSecond = currentSecond
        }
    }

    fun getMetrics(): Map<String, Any> = mapOf(
        "totalTrades" to totalTrades,
        "tradesPerSecond" to tradesPerSecond,
        "timestamp" to System.currentTimeMillis()
    )
}