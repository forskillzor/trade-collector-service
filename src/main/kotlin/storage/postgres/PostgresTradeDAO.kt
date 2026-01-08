//package com.aandios.storage.postgres
//
//import com.aandios.model.Trade
//import com.zaxxer.hikari.HikariConfig
//import com.zaxxer.hikari.HikariDataSource
//import mu.KotlinLogging
//
//private val log = KotlinLogging.logger {}
//
//class PostgresTradeDAO {
//    private val dataSource: HikariDataSource
//
//    init {
//        val config = HikariConfig().apply {
//            jdbcUrl = "jdbc:postgresql://localhost:6432/trade_collector"
//            username = "trade_user"
//            password = "strong_password_here"
//            maximumPoolSize = 10
//            minimumIdle = 2
//            connectionTimeout = 30000
//            idleTimeout = 600000
//            maxLifetime = 1800000
//            poolName = "TradePool"
//
//            // Оптимизация для массовой вставки
//            addDataSourceProperty("reWriteBatchedInserts", "true")
//            addDataSourceProperty("preparedStatementCacheQueries", "1024")
//            addDataSourceProperty("preparedStatementCacheSizeMiB", "32")
//            addDataSourceProperty("tcpKeepAlive", "true")
//        }
//
//        dataSource = HikariDataSource(config)
//        log.info { "✅ PostgreSQL DAO инициализирован" }
//    }
//
//    fun insertRawTick(trade: Trade) {
//        dataSource.connection.use { conn ->
//            conn.prepareStatement("""
//                INSERT INTO raw_ticks
//                (exchange, symbol, timestamp, price, quantity, is_buy)
//                VALUES (?, ?, ?, ?, ?, ?)
//            """).use { stmt ->
//                stmt.setString(1, trade.exchange)
//                stmt.setString(2, trade.symbol)
//                stmt.setLong(3, trade.timestamp)
//                stmt.setBigDecimal(4, trade.price.toBigDecimal())
//                stmt.setBigDecimal(5, trade.quantity.toBigDecimal())
//                stmt.setBoolean(6, trade.isBuy)
//                stmt.execute()
//            }
//        }
//    }
//
//    fun insertRawTickBatch(trades: List<Trade>) {
//        if (trades.isEmpty()) return
//
//        dataSource.connection.use { conn ->
//            conn.autoCommit = false
//            conn.prepareStatement("""
//                INSERT INTO raw_ticks
//                (exchange, symbol, timestamp, price, quantity, is_buy)
//                VALUES (?, ?, ?, ?, ?, ?)
//            """).use { stmt ->
//                trades.forEach { trade ->
//                    stmt.setString(1, trade.exchange)
//                    stmt.setString(2, trade.symbol)
//                    stmt.setLong(3, trade.timestamp)
//                    stmt.setBigDecimal(4, trade.price.toBigDecimal())
//                    stmt.setBigDecimal(5, trade.quantity.toBigDecimal())
//                    stmt.setBoolean(6, trade.isBuy)
//                    stmt.addBatch()
//                }
//                stmt.executeBatch()
//                conn.commit()
//            }
//        }
//    }
//
//    fun insertFilteredTick(trade: Trade, threshold: Double, percentile: Double) {
//        dataSource.connection.use { conn ->
//            conn.prepareStatement("""
//                INSERT INTO filtered_ticks
//                (exchange, symbol, timestamp, price, quantity, is_buy,
//                 volume_usd, threshold_value, threshold_percentile)
//                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
//            """).use { stmt ->
//                stmt.setString(1, trade.exchange)
//                stmt.setString(2, trade.symbol)
//                stmt.setLong(3, trade.timestamp)
//                stmt.setBigDecimal(4, trade.price.toBigDecimal())
//                stmt.setBigDecimal(5, trade.quantity.toBigDecimal())
//                stmt.setBoolean(6, trade.isBuy)
//
//                // Рассчитываем объём в USD
//                val volumeUsd = trade.price * trade.quantity
//                stmt.setBigDecimal(7, volumeUsd.toBigDecimal())
//                stmt.setBigDecimal(8, threshold.toBigDecimal())
//                stmt.setBigDecimal(9, percentile.toBigDecimal())
//
//                stmt.execute()
//            }
//        }
//    }
//
//    fun registerAggregateFile(
//        exchange: String,
//        symbol: String,
//        timeframe: String,
//        startTime: Long,
//        endTime: Long,
//        filePath: String,
//        fileSize: Long,
//        candleCount: Int,
//        totalTrades: Long
//    ) {
//        dataSource.connection.use { conn ->
//            conn.prepareStatement("""
//                INSERT INTO aggregates
//                (exchange, symbol, timeframe, start_time, end_time,
//                 file_path, file_size, candle_count, total_trades)
//                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
//            """).use { stmt ->
//                stmt.setString(1, exchange)
//                stmt.setString(2, symbol)
//                stmt.setString(3, timeframe)
//                stmt.setLong(4, startTime)
//                stmt.setLong(5, endTime)
//                stmt.setString(6, filePath)
//                stmt.setLong(7, fileSize)
//                stmt.setInt(8, candleCount)
//                stmt.setLong(9, totalTrades)
//                stmt.execute()
//            }
//        }
//    }
//
//    fun cleanupOldTicks(daysToKeep: Int): Int {
//        val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
//
//        return dataSource.connection.use { conn ->
//            conn.prepareStatement("""
//                DELETE FROM raw_ticks
//                WHERE timestamp < ?
//            """).use { stmt ->
//                stmt.setLong(1, cutoffTime)
//                stmt.executeUpdate()
//            }
//        }
//    }
//
//    fun getDatabaseStats(): Map<String, Any> {
//        return dataSource.connection.use { conn ->
//            conn.prepareStatement("""
//                SELECT
//                    (SELECT COUNT(*) FROM raw_ticks) as raw_count,
//                    (SELECT COUNT(*) FROM filtered_ticks) as filtered_count,
//                    (SELECT COUNT(*) FROM aggregates) as aggregates_count,
//                    pg_database_size('trade_collector') as db_size_bytes,
//                    (SELECT MAX(timestamp) FROM raw_ticks) as latest_tick
//            """).use { stmt ->
//                val rs = stmt.executeQuery()
//                if (rs.next()) {
//                    mapOf(
//                        "rawTicks" to rs.getLong("raw_count"),
//                        "filteredTicks" to rs.getLong("filtered_count"),
//                        "aggregates" to rs.getInt("aggregates_count"),
//                        "dbSizeMB" to rs.getLong("db_size_bytes") / 1024 / 1024,
//                        "latestTick" to rs.getLong("latest_tick")
//                    )
//                } else {
//                    emptyMap()
//                }
//            }
//        }
//    }
//
//    fun shutdown() {
//        dataSource.close()
//        log.info { "✅ PostgreSQL DAO остановлен" }
//    }
//}