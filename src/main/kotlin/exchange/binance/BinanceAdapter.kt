/*
 * Copyright (C) 2026 Sergey Orlov
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.aandios.exchange.binance

import com.aandios.exchange.BaseExchangeAdapter
import com.aandios.model.LiquidationOrder
import com.aandios.model.Trade
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

class BinanceAdapter : BaseExchangeAdapter("Binance") {
    override fun supportsCombinedStream(): Boolean = true

    override fun getCombinedStreamUrl(symbols: List<String>): String {
        val streams = symbols.joinToString("/") { "${it.lowercase()}@aggTrade" }
        return "wss://fstream.binance.com/market/stream?streams=$streams"
    }

    override fun getLiquidationStreamSuffix(): String = "@forceOrder"

    override fun getCombinedStreamUrlWithLiq(symbols: List<String>, includeLiquidations: Boolean): String {
        val streams = symbols.flatMap { symbol ->
            val lower = symbol.lowercase()
            if (includeLiquidations) {
                listOf("$lower@aggTrade", "$lower${getLiquidationStreamSuffix()}")
            } else {
                listOf("$lower@aggTrade")
            }
        }.joinToString("/")
        return "wss://fstream.binance.com/market/stream?streams=$streams"
    }

    override fun getWebSocketUrl(symbol: String): String {
        return "wss://fstream.binance.com/market/ws/${symbol.lowercase()}@aggTrade"
    }

    override fun parseCombinedFrame(json: String): Pair<String, JsonNode>? {
        return try {
            val root = mapper.readTree(json)
            val stream = root["stream"]?.asText() ?: return null
            val data = root["data"] ?: return null
            val symbol = stream.substringBefore("@").uppercase()
            symbol to data
        } catch (e: Exception) {
            null
        }
    }

    override fun isTradeMessageNode(node: JsonNode): Boolean {
        return node.has("e") && node["e"].asText() == "aggTrade"
    }

    override fun isLiquidationMessageNode(node: JsonNode): Boolean {
        return node.has("e") && node["e"].asText() == "forceOrder"
    }

    override fun parseLiquidationNode(node: JsonNode, symbol: String): LiquidationOrder? {
        return try {
            val o = node["o"] ?: return null
            val side = o["S"].asText()
            LiquidationOrder(
                exchange = name,
                symbol = symbol.uppercase(),
                timestamp = o["T"].asLong(),
                price = BigDecimal(o["ap"].asText()),
                quantity = BigDecimal(o["q"].asText()),
                isLong = side == "SELL",
                orderType = o["o"].asText()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun parseTradeNode(node: JsonNode, symbol: String): Trade? {
        return try {
            Trade(
                exchange = name,
                symbol = symbol.uppercase(),
                timestamp = node["T"].asLong(),
                price = BigDecimal(node["p"].asText()),
                quantity = BigDecimal(node["q"].asText()),
                isBuy = !node["m"].asBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun parseTrade(json: String, symbol: String): Trade? {
        return try {
            val node = mapper.readTree(json)
            Trade(
                exchange = name,
                symbol = symbol.uppercase(),
                timestamp = node["T"].asLong(),
                price = BigDecimal(node["p"].asText()),
                quantity = BigDecimal(node["q"].asText()),
                isBuy = !node["m"].asBoolean()
            )
        } catch (e: Exception) {
            null
        }
    }

    override fun isTradeMessage(json: String): Boolean {
        return try {
            val node = mapper.readTree(json)
            node.has("e") && node["e"].asText() == "aggTrade"
        } catch (e: Exception) {
            false
        }
    }
}