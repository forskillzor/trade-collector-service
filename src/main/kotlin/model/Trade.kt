package com.aandios.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.math.BigDecimal

data class Trade(
    val exchange: String,
    val symbol: String,
    val timestamp: Long,
    val price: Double,
    val quantity: Double,
    val isBuy: Boolean
) {
    fun toLocalDateTime(): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneOffset.UTC
        )
    }

    fun getVolumeUsd(): Double = price * quantity

    companion object {
        fun fromRaw(
            exchange: String,
            symbol: String,
            timestamp: Long,
            price: BigDecimal,
            quantity: BigDecimal,
            isBuy: Boolean
        ): Trade {
            return Trade(
                exchange = exchange,
                symbol = symbol,
                timestamp = timestamp,
                price = price.toDouble(),
                quantity = quantity.toDouble(),
                isBuy = isBuy
            )
        }
    }
}