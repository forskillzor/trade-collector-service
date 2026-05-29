package com.aandios.storage.postgres

import com.aandios.config.DatabaseConfig
import com.aandios.model.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import java.math.BigDecimal
import java.util.*

private val log = KotlinLogging.logger {}

class TradeDAO(
    private val dataSource: HikariDataSource
) {
    companion object {
        fun createDataSource(config: DatabaseConfig): HikariDataSource {
            val hikariConfig = HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://${config.resolvedHost}:${config.resolvedPort}/${config.resolvedDatabase}"
                username = config.resolvedUsername
                password = config.resolvedPassword
                maximumPoolSize = 15
                minimumIdle = 5
                connectionTimeout = 30000
                idleTimeout = 600000
                maxLifetime = 1800000
                poolName = "TradePool"

                // Оптимизация для массовой вставки
                addDataSourceProperty("reWriteBatchedInserts", "true")
                addDataSourceProperty("preparedStatementCacheQueries", "1024")
                addDataSourceProperty("preparedStatementCacheSizeMiB", "32")
                addDataSourceProperty("tcpKeepAlive", "true")
            }

            return HikariDataSource(hikariConfig)
        }
    }

    // ========== RAW TRADES ==========

    fun insertRawTrade(trade: Trade) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO raw_trades 
                (exchange, symbol, timestamp, price, quantity, is_buy)
                VALUES (?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                stmt.setString(1, trade.exchange)
                stmt.setString(2, trade.symbol)
                stmt.setLong(3, trade.timestamp)
                stmt.setBigDecimal(4, BigDecimal.valueOf(trade.price))
                stmt.setBigDecimal(5, BigDecimal.valueOf(trade.quantity))
                stmt.setBoolean(6, trade.isBuy)
                stmt.execute()
            }
        }
    }

    fun insertRawTradesBatch(trades: List<Trade>) {
        if (trades.isEmpty()) return

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("""
                INSERT INTO raw_trades 
                (exchange, symbol, timestamp, price, quantity, is_buy)
                VALUES (?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                trades.forEach { trade ->
                    stmt.setString(1, trade.exchange)
                    stmt.setString(2, trade.symbol)
                    stmt.setLong(3, trade.timestamp)
                    stmt.setBigDecimal(4, BigDecimal.valueOf(trade.price))
                    stmt.setBigDecimal(5, BigDecimal.valueOf(trade.quantity))
                    stmt.setBoolean(6, trade.isBuy)
                    stmt.addBatch()
                }
                stmt.executeBatch()
                conn.commit()
            }
        }
    }

    fun getRecentRawTrades(
        exchange: String,
        symbol: String,
        limit: Int = 1000000
    ): List<Trade> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT exchange, symbol, timestamp, price, quantity, is_buy
                FROM raw_trades 
                WHERE exchange = ? AND symbol = ?
                ORDER BY timestamp DESC
                LIMIT ?
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)
                stmt.setInt(3, limit)

                val rs = stmt.executeQuery()
                val trades = mutableListOf<Trade>()

                while (rs.next()) {
                    trades.add(Trade.fromRaw(
                        exchange = rs.getString("exchange"),
                        symbol = rs.getString("symbol"),
                        timestamp = rs.getLong("timestamp"),
                        price = rs.getBigDecimal("price"),
                        quantity = rs.getBigDecimal("quantity"),
                        isBuy = rs.getBoolean("is_buy")
                    ))
                }
                trades.reversed() // возвращаем в хронологическом порядке
            }
        }
    }

    fun getRawTradesCount(exchange: String, symbol: String): Long {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT COUNT(*) as count 
                FROM raw_trades 
                WHERE exchange = ? AND symbol = ?
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)

                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong("count") else 0L
            }
        }
    }

    fun cleanupOldRawTrades(): Int {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT cleanup_old_raw_trades() as deleted").use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getInt("deleted") else 0
            }
        }
    }

    // ========== FILTERED TRADES ==========

    fun insertFilteredTrade(filteredTrade: FilteredTrade) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO filtered_trades 
                (exchange, symbol, timestamp, price, quantity, is_buy,
                 volume_usd, percentile_threshold, volume_threshold, trade_category,
                 window_start_time, window_end_time, window_total_trades, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                val trade = filteredTrade.trade
                stmt.setString(1, trade.exchange)
                stmt.setString(2, trade.symbol)
                stmt.setLong(3, trade.timestamp)
                stmt.setBigDecimal(4, BigDecimal.valueOf(trade.price))
                stmt.setBigDecimal(5, BigDecimal.valueOf(trade.quantity))
                stmt.setBoolean(6, trade.isBuy)
                stmt.setBigDecimal(7, filteredTrade.volumeUsd)
                stmt.setDouble(8, filteredTrade.percentileThreshold)
                stmt.setBigDecimal(9, filteredTrade.volumeThreshold)
                stmt.setString(10, filteredTrade.tradeCategory?.name)
                stmt.setLong(11, filteredTrade.windowStartTime)
                stmt.setLong(12, filteredTrade.windowEndTime)
                stmt.setInt(13, filteredTrade.windowTotalTrades)
                stmt.setObject(14, UUID.randomUUID())

                stmt.execute()
            }
        }
    }

    fun insertFilteredTradesBatch(filteredTrades: List<FilteredTrade>) {
        if (filteredTrades.isEmpty()) return

        val batchId = UUID.randomUUID()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("""
                INSERT INTO filtered_trades 
                (exchange, symbol, timestamp, price, quantity, is_buy,
                 volume_usd, percentile_threshold, volume_threshold, trade_category,
                 window_start_time, window_end_time, window_total_trades, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                filteredTrades.forEach { filteredTrade ->
                    val trade = filteredTrade.trade
                    stmt.setString(1, trade.exchange)
                    stmt.setString(2, trade.symbol)
                    stmt.setLong(3, trade.timestamp)
                    stmt.setBigDecimal(4, BigDecimal.valueOf(trade.price))
                    stmt.setBigDecimal(5, BigDecimal.valueOf(trade.quantity))
                    stmt.setBoolean(6, trade.isBuy)
                    stmt.setBigDecimal(7, filteredTrade.volumeUsd)
                    stmt.setDouble(8, filteredTrade.percentileThreshold)
                    stmt.setBigDecimal(9, filteredTrade.volumeThreshold)
                    stmt.setString(10, filteredTrade.tradeCategory?.name)
                    stmt.setLong(11, filteredTrade.windowStartTime)
                    stmt.setLong(12, filteredTrade.windowEndTime)
                    stmt.setInt(13, filteredTrade.windowTotalTrades)
                    stmt.setObject(14, batchId)

                    stmt.addBatch()
                }
                stmt.executeBatch()
                conn.commit()
            }
        }
    }

    // ========== AGGREGATES (ARROW FILES) ==========

    fun saveAggregate(aggregate: AggregateCandle) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO aggregates 
                (exchange, symbol, timeframe, start_time, end_time,
                 price_levels_jsonb, total_ticks,
                 min_price, max_price, price_levels)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (exchange, symbol, timeframe, start_time, end_time) 
                DO UPDATE SET
                    price_levels_jsonb = EXCLUDED.price_levels_jsonb,
                    total_ticks = EXCLUDED.total_ticks,
                    min_price = EXCLUDED.min_price,
                    max_price = EXCLUDED.max_price,
                    price_levels = EXCLUDED.price_levels,
                    updated_at = CURRENT_TIMESTAMP
            """).use { stmt ->
                stmt.setString(1, aggregate.exchange)
                stmt.setString(2, aggregate.symbol)
                stmt.setString(3, aggregate.timeframe)
                stmt.setLong(4, aggregate.startTime)
                stmt.setLong(5, aggregate.endTime)
                stmt.setString(6, aggregate.priceLevelsJson)
                stmt.setLong(7, aggregate.totalTicks)
                stmt.setBigDecimal(8, aggregate.minPrice)
                stmt.setBigDecimal(9, aggregate.maxPrice)
                stmt.setInt(10, aggregate.priceLevels)

                stmt.execute()
            }
        }
    }

    fun getAggregate(
        exchange: String,
        symbol: String,
        timeframe: String,
        startTime: Long,
        endTime: Long
    ): AggregateCandle? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT price_levels_jsonb, total_ticks, min_price, max_price, price_levels
                FROM aggregates
                WHERE exchange = ? AND symbol = ? AND timeframe = ? 
                  AND start_time = ? AND end_time = ?
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)
                stmt.setString(3, timeframe)
                stmt.setLong(4, startTime)
                stmt.setLong(5, endTime)

                val rs = stmt.executeQuery()
                if (rs.next()) {
                    AggregateCandle(
                        exchange = exchange,
                        symbol = symbol,
                        timeframe = timeframe,
                        startTime = startTime,
                        endTime = endTime,
                        priceLevelsJson = rs.getString("price_levels_jsonb"),
                        totalTicks = rs.getLong("total_ticks"),
                        minPrice = rs.getBigDecimal("min_price"),
                        maxPrice = rs.getBigDecimal("max_price"),
                        priceLevels = rs.getInt("price_levels")
                    )
                } else {
                    null
                }
            }
        }
    }

    // ========== VOLUME WINDOWS ==========

    fun saveVolumeWindow(window: VolumeWindow) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO volume_windows
                (exchange, symbol, start_time, end_time, total_trades,
                 min_volume, max_volume, avg_volume, median_volume, stddev_volume,
                 p50_volume, p95_volume, p98_volume, p99_volume,
                 filter_percentile, filter_threshold)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (exchange, symbol, start_time, end_time) 
                DO UPDATE SET
                    total_trades = EXCLUDED.total_trades,
                    min_volume = EXCLUDED.min_volume,
                    max_volume = EXCLUDED.max_volume,
                    avg_volume = EXCLUDED.avg_volume,
                    median_volume = EXCLUDED.median_volume,
                    stddev_volume = EXCLUDED.stddev_volume,
                    p50_volume = EXCLUDED.p50_volume,
                    p95_volume = EXCLUDED.p95_volume,
                    p98_volume = EXCLUDED.p98_volume,
                    p99_volume = EXCLUDED.p99_volume,
                    filter_percentile = EXCLUDED.filter_percentile,
                    filter_threshold = EXCLUDED.filter_threshold
            """).use { stmt ->
                stmt.setString(1, window.exchange)
                stmt.setString(2, window.symbol)
                stmt.setLong(3, window.startTime)
                stmt.setLong(4, window.endTime)
                stmt.setInt(5, window.totalTrades)
                stmt.setBigDecimal(6, window.minVolume)
                stmt.setBigDecimal(7, window.maxVolume)
                stmt.setBigDecimal(8, window.avgVolume)
                stmt.setBigDecimal(9, window.medianVolume)
                stmt.setBigDecimal(10, window.stddevVolume)
                stmt.setBigDecimal(11, window.p50Volume)
                stmt.setBigDecimal(12, window.p95Volume)
                stmt.setBigDecimal(13, window.p98Volume)
                stmt.setBigDecimal(14, window.p99Volume)
                stmt.setDouble(15, window.filterPercentile)
                stmt.setBigDecimal(16, window.filterThreshold)

                stmt.execute()
            }
        }
    }

    fun getLatestVolumeWindow(
        exchange: String,
        symbol: String
    ): VolumeWindow? {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM volume_windows
                WHERE exchange = ? AND symbol = ?
                ORDER BY end_time DESC
                LIMIT 1
            """).use { stmt ->
                stmt.setString(1, exchange)
                stmt.setString(2, symbol)

                val rs = stmt.executeQuery()
                if (rs.next()) {
                    VolumeWindow(
                        exchange = rs.getString("exchange"),
                        symbol = rs.getString("symbol"),
                        startTime = rs.getLong("start_time"),
                        endTime = rs.getLong("end_time"),
                        totalTrades = rs.getInt("total_trades"),
                        minVolume = rs.getBigDecimal("min_volume"),
                        maxVolume = rs.getBigDecimal("max_volume"),
                        avgVolume = rs.getBigDecimal("avg_volume"),
                        medianVolume = rs.getBigDecimal("median_volume"),
                        stddevVolume = rs.getBigDecimal("stddev_volume"),
                        p50Volume = rs.getBigDecimal("p50_volume"),
                        p95Volume = rs.getBigDecimal("p95_volume"),
                        p98Volume = rs.getBigDecimal("p98_volume"),
                        p99Volume = rs.getBigDecimal("p99_volume"),
                        filterPercentile = rs.getDouble("filter_percentile"),
                        filterThreshold = rs.getBigDecimal("filter_threshold")
                    )
                } else {
                    null
                }
            }
        }
    }

    // ========== STATISTКА БД ==========

    fun getDatabaseStats(): Map<String, Any> {
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT 
                    (SELECT COUNT(*) FROM raw_trades) as raw_trades_count,
                    (SELECT COUNT(*) FROM filtered_trades) as filtered_trades_count,
                    (SELECT COUNT(*) FROM aggregates) as aggregates_count,
                    (SELECT COUNT(*) FROM volume_windows) as windows_count,
                    pg_database_size(current_database()) as db_size_bytes,
                    (SELECT MAX(timestamp) FROM raw_trades) as latest_raw_trade,
                    (SELECT MAX(timestamp) FROM filtered_trades) as latest_filtered_trade
            """).use { stmt ->
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    mapOf(
                        "rawTrades" to rs.getLong("raw_trades_count"),
                        "filteredTrades" to rs.getLong("filtered_trades_count"),
                        "aggregates" to rs.getInt("aggregates_count"),
                        "windows" to rs.getInt("windows_count"),
                        "dbSizeMB" to (rs.getLong("db_size_bytes") / 1024 / 1024),
                        "latestRawTrade" to rs.getLong("latest_raw_trade"),
                        "latestFilteredTrade" to rs.getLong("latest_filtered_trade")
                    )
                } else {
                    emptyMap()
                }
            }
        }
    }

    fun shutdown() {
        dataSource.close()
    }
}