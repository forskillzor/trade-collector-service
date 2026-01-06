package com.aandios.storage

import com.aandios.model.Trade
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

class TradeDAO {
    object Trades : Table() {
        val ts = long("ts")
        val price = double("price")
        val qty = double("qty")
        val direction = bool("direction") // true = bid, false = ask
    }

    init {
        transaction {
            SchemaUtils.create(Trades)
        }
    }

    fun insert(trade: Trade) {
        transaction {
            Trades.insert {
                it[ts] = trade.timestamp
                it[price] = trade.price
                it[qty] = trade.quantity
                it[direction] = trade.direction
            }
        }
    }
}