package com.aandios.exchange

import com.aandios.model.Trade
import com.fasterxml.jackson.databind.JsonNode

interface ExchangeAdapter {
    val name: String
    fun getWebSocketUrl(symbol: String): String
    fun getSubscribeMessage(symbol: String): String?
    fun parseTrade(json: String, symbol: String): Trade?
    fun isTradeMessage(json: String): Boolean

    fun supportsCombinedStream(): Boolean = false
    fun getCombinedStreamUrl(symbols: List<String>): String = ""

    fun parseCombinedFrame(json: String): Pair<String, JsonNode>? = null
    fun isTradeMessageNode(node: JsonNode): Boolean = isTradeMessage(node.toString())
    fun parseTradeNode(node: JsonNode, symbol: String): Trade? = parseTrade(node.toString(), symbol)
}