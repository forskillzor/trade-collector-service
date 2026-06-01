package com.aandios.service

import com.aandios.model.Trade
import com.aandios.storage.postgres.TradeDAO
import mu.KotlinLogging
import java.io.File
import java.math.BigDecimal

private val log = KotlinLogging.logger {}

class DiskBuffer(private val dataDir: String) {
    private val bufferFile: File

    init {
        File(dataDir).mkdirs()
        bufferFile = File(dataDir, "disk_buffer.jsonl")
    }

    @Synchronized
    fun saveBatch(trades: List<Trade>) {
        try {
            bufferFile.bufferedWriter().use { writer ->
                trades.forEach { trade ->
                    writer.appendLine(
                        """{"exchange":"${trade.exchange}","symbol":"${trade.symbol}","timestamp":${trade.timestamp},"price":"${trade.price}","quantity":"${trade.quantity}","is_buy":${trade.isBuy}}"""
                    )
                }
            }
            log.warn { "DiskBuffer saved ${trades.size} trades (total=${size()})" }
        } catch (e: Exception) {
            log.error(e) { "DiskBuffer write error" }
        }
    }

    @Synchronized
    fun replayTo(dao: TradeDAO): Boolean {
        if (!hasPending()) return false

        try {
            val lines = bufferFile.readLines()
            if (lines.isEmpty()) {
                bufferFile.delete()
                return false
            }

            val trades = lines.mapNotNull { line ->
                try {
                    val parts = line.removeSurrounding("{", "}")
                        .split(",")
                        .associate { part ->
                            val (key, value) = part.split(":", limit = 2)
                            key.trim('"') to value.trim('"')
                        }
                    Trade(
                        exchange = parts["exchange"] ?: return@mapNotNull null,
                        symbol = parts["symbol"] ?: return@mapNotNull null,
                        timestamp = parts["timestamp"]?.toLongOrNull() ?: return@mapNotNull null,
                        price = parts["price"]?.let { BigDecimal(it) } ?: return@mapNotNull null,
                        quantity = parts["quantity"]?.let { BigDecimal(it) } ?: return@mapNotNull null,
                        isBuy = parts["is_buy"]?.toBooleanStrictOrNull() ?: return@mapNotNull null
                    )
                } catch (e: Exception) {
                    log.warn { "DiskBuffer corrupt line: $line" }
                    null
                }
            }

            if (trades.isNotEmpty()) {
                dao.insertRawTradesBatch(trades)
                log.info { "DiskBuffer replayed ${trades.size} trades" }
            }

            bufferFile.delete()
            return true
        } catch (e: Exception) {
            log.error(e) { "DiskBuffer replay error" }
            return false
        }
    }

    @Synchronized
    fun hasPending(): Boolean = bufferFile.exists() && bufferFile.length() > 0

    @Synchronized
    fun size(): Long = if (bufferFile.exists()) bufferFile.length() else 0L
}
