package com.aandios.storage.postgres

import com.aandios.config.DatabaseConfig
import com.aandios.model.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import java.util.*
import java.util.concurrent.ConcurrentHashMap

private val log = KotlinLogging.logger {}

class TradeDAO(
    private val dataSource: HikariDataSource
) {
    val connection get() = dataSource.connection
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
                addDataSourceProperty("reWriteBatchedInserts", "true")
                addDataSourceProperty("preparedStatementCacheQueries", "1024")
                addDataSourceProperty("preparedStatementCacheSizeMiB", "32")
                addDataSourceProperty("tcpKeepAlive", "true")
                leakDetectionThreshold = 2000
                keepaliveTime = 300_000
                connectionTestQuery = "SELECT 1"
            }
            return HikariDataSource(hikariConfig)
        }
    }

    private val ensuredTables = ConcurrentHashMap.newKeySet<String>()

    private fun tableName(prefix: String, symbol: String): String {
        return "${prefix}_${symbol.lowercase()}"
    }

    private fun ensureTables(symbol: String) {
        val key = symbol.lowercase()
        if (!ensuredTables.add(key)) return

        dataSource.connection.use { conn ->
            val stmt = conn.createStatement()

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${tableName("raw_trades", symbol)} (
                    id          BIGSERIAL PRIMARY KEY,
                    exchange    VARCHAR(20)    NOT NULL,
                    symbol      VARCHAR(20)    NOT NULL,
                    timestamp   BIGINT         NOT NULL,
                    price       DECIMAL(20,8)  NOT NULL,
                    quantity    DECIMAL(30,8)  NOT NULL,
                    is_buy      BOOLEAN        NOT NULL,
                    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            """)
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("raw_trades", symbol)}_ts ON ${tableName("raw_trades", symbol)} (timestamp DESC)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${tableName("aggregates", symbol)} (
                    id                  UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
                    exchange            VARCHAR(20)    NOT NULL,
                    symbol              VARCHAR(20)    NOT NULL,
                    timeframe           VARCHAR(10)    NOT NULL CHECK (timeframe IN ('1m','5m','15m','30m','1h','4h','1d')),
                    start_time          BIGINT         NOT NULL,
                    end_time            BIGINT         NOT NULL,
                    price_levels_jsonb  JSONB          NOT NULL,
                    total_ticks         BIGINT         NOT NULL,
                    min_price           DECIMAL(20,8)  NOT NULL,
                    max_price           DECIMAL(20,8)  NOT NULL,
                    price_levels        INTEGER        NOT NULL,
                    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (exchange, symbol, timeframe, start_time, end_time)
                )
            """)
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("aggregates", symbol)}_tf ON ${tableName("aggregates", symbol)} (timeframe)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("aggregates", symbol)}_time ON ${tableName("aggregates", symbol)} (start_time, end_time)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${tableName("filtered_trades", symbol)} (
                    id                   BIGSERIAL PRIMARY KEY,
                    exchange             VARCHAR(20)    NOT NULL,
                    symbol               VARCHAR(20)    NOT NULL,
                    timestamp            BIGINT         NOT NULL,
                    price                DECIMAL(20,8)  NOT NULL,
                    quantity             DECIMAL(30,8)  NOT NULL,
                    is_buy               BOOLEAN        NOT NULL,
                    volume_usd           DECIMAL(30,2)  NOT NULL,
                    percentile_threshold DECIMAL(5,2)   NOT NULL,
                    volume_threshold     DECIMAL(30,8)  NOT NULL,
                    trade_category       VARCHAR(20),
                    window_start_time    BIGINT         NOT NULL,
                    window_end_time      BIGINT         NOT NULL,
                    window_total_trades  INTEGER        NOT NULL,
                    created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    batch_id             UUID
                )
            """)
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("filtered_trades", symbol)}_ts ON ${tableName("filtered_trades", symbol)} (timestamp DESC)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("filtered_trades", symbol)}_vol ON ${tableName("filtered_trades", symbol)} (volume_usd DESC)")
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_${tableName("filtered_trades", symbol)}_cat ON ${tableName("filtered_trades", symbol)} (trade_category)")
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_${tableName("filtered_trades", symbol)}_uniq ON ${tableName("filtered_trades", symbol)} (timestamp, price, quantity, is_buy)")

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS ${tableName("volume_windows", symbol)} (
                    id                UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
                    exchange          VARCHAR(20) NOT NULL,
                    symbol            VARCHAR(20) NOT NULL,
                    start_time        BIGINT      NOT NULL,
                    end_time          BIGINT      NOT NULL,
                    total_trades      INTEGER     NOT NULL,
                    min_volume        DECIMAL(30,8),
                    max_volume        DECIMAL(30,8),
                    avg_volume        DECIMAL(30,8),
                    median_volume     DECIMAL(30,8),
                    stddev_volume     DECIMAL(30,8),
                    p50_volume        DECIMAL(30,8),
                    p95_volume        DECIMAL(30,8),
                    p98_volume        DECIMAL(30,8),
                    p99_volume        DECIMAL(30,8),
                    filter_percentile DECIMAL(5,2) DEFAULT 0.98,
                    filter_threshold  DECIMAL(30,8),
                    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE (exchange, symbol, start_time, end_time)
                )
            """)
        }
    }

    // ========== RAW TRADES ==========

    fun insertRawTradesBatch(trades: List<Trade>) {
        if (trades.isEmpty()) return
        val symbol = trades.first().symbol
        ensureTables(symbol)

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("""
                INSERT INTO ${tableName("raw_trades", symbol)} 
                (exchange, symbol, timestamp, price, quantity, is_buy)
                VALUES (?, ?, ?, ?, ?, ?)
            """).use { stmt ->
                trades.forEach { trade ->
                    stmt.setString(1, trade.exchange)
                    stmt.setString(2, trade.symbol)
                    stmt.setLong(3, trade.timestamp)
                    stmt.setBigDecimal(4, trade.price)
                    stmt.setBigDecimal(5, trade.quantity)
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
        ensureTables(symbol)
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT exchange, symbol, timestamp, price, quantity, is_buy
                FROM ${tableName("raw_trades", symbol)} 
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
                trades.reversed()
            }
        }
    }

    fun cleanupOldRawTrades(symbol: String, maxRows: Long = 10_000): Int {
        ensureTables(symbol)
        return dataSource.connection.use { conn ->
            val stmt = conn.createStatement()
            val rs = stmt.executeQuery("""
                WITH cnt AS (SELECT COUNT(*) as total FROM ${tableName("raw_trades", symbol)})
                SELECT total FROM cnt WHERE total > $maxRows
            """)
            if (!rs.next()) return 0
            val total = rs.getLong("total")
            val toDelete = total - maxRows
            stmt.execute("""
                DELETE FROM ${tableName("raw_trades", symbol)}
                WHERE id IN (
                    SELECT id FROM ${tableName("raw_trades", symbol)}
                    ORDER BY timestamp ASC LIMIT $toDelete
                )
            """)
            stmt.executeQuery("SELECT $toDelete").use { it.next() }
            toDelete.toInt()
        }
    }

    // ========== FILTERED TRADES ==========

    fun insertFilteredTradesBatch(filteredTrades: List<FilteredTrade>) {
        if (filteredTrades.isEmpty()) return
        val symbol = filteredTrades.first().trade.symbol
        ensureTables(symbol)
        val batchId = UUID.randomUUID()

        dataSource.connection.use { conn ->
            conn.autoCommit = false
            conn.prepareStatement("""
                INSERT INTO ${tableName("filtered_trades", symbol)} 
                (exchange, symbol, timestamp, price, quantity, is_buy,
                 volume_usd, percentile_threshold, volume_threshold, trade_category,
                 window_start_time, window_end_time, window_total_trades, batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (timestamp, price, quantity, is_buy) DO NOTHING
            """).use { stmt ->
                filteredTrades.forEach { filteredTrade ->
                    val trade = filteredTrade.trade
                    stmt.setString(1, trade.exchange)
                    stmt.setString(2, trade.symbol)
                    stmt.setLong(3, trade.timestamp)
                    stmt.setBigDecimal(4, trade.price)
                    stmt.setBigDecimal(5, trade.quantity)
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

    // ========== AGGREGATES ==========

    fun saveAggregate(aggregate: AggregateCandle) {
        ensureTables(aggregate.symbol)
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO ${tableName("aggregates", aggregate.symbol)} 
                (exchange, symbol, timeframe, start_time, end_time,
                 price_levels_jsonb, total_ticks, min_price, max_price, price_levels)
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

    /** Load 1m aggregates for merging into higher timeframes */
    fun get1mAggregates(symbol: String, start: Long, end: Long): List<AggregateCandle> {
        ensureTables(symbol)
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT exchange, symbol, timeframe, start_time, end_time,
                       price_levels_jsonb, total_ticks, min_price, max_price, price_levels
                FROM ${tableName("aggregates", symbol)}
                WHERE timeframe = '1m' AND start_time >= ? AND start_time < ?
                ORDER BY start_time
            """).use { stmt ->
                stmt.setLong(1, start)
                stmt.setLong(2, end)
                val rs = stmt.executeQuery()
                val results = mutableListOf<AggregateCandle>()
                while (rs.next()) {
                    results.add(AggregateCandle(
                        exchange = rs.getString("exchange"),
                        symbol = rs.getString("symbol"),
                        timeframe = rs.getString("timeframe"),
                        startTime = rs.getLong("start_time"),
                        endTime = rs.getLong("end_time"),
                        priceLevelsJson = rs.getString("price_levels_jsonb"),
                        totalTicks = rs.getLong("total_ticks"),
                        minPrice = rs.getBigDecimal("min_price"),
                        maxPrice = rs.getBigDecimal("max_price"),
                        priceLevels = rs.getInt("price_levels")
                    ))
                }
                results
            }
        }
    }

    // ========== VOLUME WINDOWS ==========

    fun saveVolumeWindow(window: VolumeWindow) {
        ensureTables(window.symbol)
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO ${tableName("volume_windows", window.symbol)}
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

    /** Delete volume_windows and filtered_trades older than retentionMs */
    fun cleanupOldDerivedData(symbol: String, retentionMs: Long = 86_400_000L) {
        ensureTables(symbol)
        val cutoff = System.currentTimeMillis() - retentionMs
        dataSource.connection.use { conn ->
            val stmt = conn.createStatement()

            // Volume windows: keep last 24h
            val vwDeleted = stmt.executeUpdate("""
                DELETE FROM ${tableName("volume_windows", symbol)}
                WHERE end_time < $cutoff
            """)

            // Filtered trades: keep last 24h
            val ftDeleted = stmt.executeUpdate("""
                DELETE FROM ${tableName("filtered_trades", symbol)}
                WHERE timestamp < $cutoff
            """)

            if (vwDeleted > 0 || ftDeleted > 0) {
                log.info { "Cleanup $symbol: $vwDeleted volume_windows, $ftDeleted filtered_trades" }
            }
        }
    }

    // ========== STATISTICS ==========

    fun getDatabaseStats(): Map<String, Any> {
        return try {
            dataSource.connection.use { conn ->
                val stmt = conn.createStatement()
                val rs = stmt.executeQuery("SELECT pg_database_size(current_database()) as db_size_bytes")
                val dbSize = if (rs.next()) rs.getLong("db_size_bytes") / 1024 / 1024 else 0L

                val tableCounts = mutableMapOf<String, Long>()
                val tableList = conn.createStatement().executeQuery("""
                    SELECT tablename FROM pg_catalog.pg_tables 
                    WHERE schemaname = 'public' AND tablename LIKE 'raw_trades_%'
                """)
                var totalRaw = 0L
                while (tableList.next()) {
                    val tbl = tableList.getString("tablename")
                    val cntRs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM $tbl")
                    if (cntRs.next()) {
                        val cnt = cntRs.getLong(1)
                        tableCounts[tbl] = cnt
                        totalRaw += cnt
                    }
                }

                mapOf(
                    "rawTrades" to totalRaw,
                    "tableCounts" to tableCounts,
                    "dbSizeMB" to dbSize,
                    "timestamp" to System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            log.warn(e) { "DB stats error" }
            emptyMap()
        }
    }

    fun getHistory(symbol: String, minutes: Int = 60): List<Map<String, Any?>> {
        ensureTables(symbol)
        val cutoff = System.currentTimeMillis() - (minutes * 60_000L)
        return dataSource.connection.use { conn ->
            conn.prepareStatement("""
                WITH minutes AS (
                    SELECT generate_series(
                        ?,
                        COALESCE((SELECT MAX(start_time) FROM ${tableName("aggregates", symbol)}), ?),
                        60000
                    ) AS minute_start
                ),
                agg AS (
                    SELECT start_time, 1 as has_agg FROM ${tableName("aggregates", symbol)}
                    WHERE timeframe = '1m' AND start_time >= ?
                ),
                raw AS (
                    SELECT (timestamp / 60000) * 60000 AS minute_start, 1 as has_raw
                    FROM ${tableName("raw_trades", symbol)}
                    WHERE timestamp >= ?
                    GROUP BY minute_start
                )
                SELECT m.minute_start,
                       COALESCE(a.has_agg, 0) as has_agg,
                       COALESCE(r.has_raw, 0) as has_raw
                FROM minutes m
                LEFT JOIN agg a ON a.start_time = m.minute_start
                LEFT JOIN raw r ON r.minute_start = m.minute_start
                ORDER BY m.minute_start DESC
                LIMIT ?
            """).use { stmt ->
                stmt.setLong(1, cutoff)
                stmt.setLong(2, cutoff)
                stmt.setLong(3, cutoff)
                stmt.setLong(4, cutoff)
                stmt.setInt(5, minutes)
                val rs = stmt.executeQuery()
                val results = mutableListOf<Map<String, Any?>>()
                while (rs.next()) {
                    results.add(mapOf(
                        "minute" to rs.getLong("minute_start"),
                        "hasAgg" to (rs.getInt("has_agg") > 0),
                        "hasRaw" to (rs.getInt("has_raw") > 0)
                    ))
                }
                results.reversed()
            }
        }
    }

    fun ping(): Boolean {
        return try {
            dataSource.connection.use { conn ->
                conn.prepareStatement("SELECT 1").use { stmt ->
                    stmt.executeQuery().use { it.next() }
                }
            }
            true
        } catch (e: Exception) {
            log.warn(e) { "DB ping failed" }
            false
        }
    }

    fun shutdown() {
        dataSource.close()
    }
}
