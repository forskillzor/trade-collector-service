-- 1. Таблица для сырых сделок (raw_trades)
CREATE TABLE IF NOT EXISTS raw_trades (
                                          id BIGSERIAL NOT NULL,
                                          exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    timestamp BIGINT NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    quantity DECIMAL(30, 8) NOT NULL,
    is_buy BOOLEAN NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, timestamp)
    ) PARTITION BY RANGE (timestamp);

-- Индексы для быстрого поиска
CREATE INDEX IF NOT EXISTS idx_raw_trades_exchange_symbol ON raw_trades(exchange, symbol);
CREATE INDEX IF NOT EXISTS idx_raw_trades_timestamp ON raw_trades(timestamp DESC);

-- 2. Таблица для агрегированных данных (свечи по ценам)
CREATE TABLE IF NOT EXISTS aggregates (
                                          id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    timeframe VARCHAR(10) NOT NULL CHECK (timeframe IN ('1m', '5m', '15m', '30m', '1h', '4h', '1d')),

    -- Временной диапазон
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,

    -- Файл Arrow с данными
    arrow_file_path TEXT NOT NULL,
    arrow_file_size BIGINT NOT NULL,
    compression_type VARCHAR(20) DEFAULT 'zstd',

    -- Метаданные о файле
    total_ticks BIGINT NOT NULL,
    min_price DECIMAL(20, 8) NOT NULL,
    max_price DECIMAL(20, 8) NOT NULL,
    price_levels INTEGER NOT NULL, -- количество уникальных цен в свече

-- Технические поля
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    -- Уникальность по времени и инструменту
    UNIQUE(exchange, symbol, timeframe, start_time, end_time)
    );

-- Индексы для агрегатов
CREATE INDEX IF NOT EXISTS idx_aggregates_exchange_symbol ON aggregates(exchange, symbol);
CREATE INDEX IF NOT EXISTS idx_aggregates_timeframe ON aggregates(timeframe);
CREATE INDEX IF NOT EXISTS idx_aggregates_time_range ON aggregates(start_time, end_time);

-- 3. Таблица для фильтрованных сделок (big trades)
CREATE TABLE IF NOT EXISTS filtered_trades (
                                               id BIGSERIAL NOT NULL,
                                               exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    timestamp BIGINT NOT NULL,
    price DECIMAL(20, 8) NOT NULL,
    quantity DECIMAL(30, 8) NOT NULL,
    is_buy BOOLEAN NOT NULL,

    -- Фильтровочные метрики
    volume_usd DECIMAL(30, 2) NOT NULL,
    percentile_threshold DECIMAL(5, 2) NOT NULL, -- например 0.98 (98%)
    volume_threshold DECIMAL(30, 8) NOT NULL, -- пороговый объём на момент сделки
    trade_category VARCHAR(20), -- 'large', 'very_large', 'whale'

-- Ссылка на окно выборки
    window_start_time BIGINT NOT NULL,
    window_end_time BIGINT NOT NULL,
    window_total_trades INTEGER NOT NULL,

    -- Технические поля
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    batch_id UUID,

    PRIMARY KEY (id, timestamp)
    ) PARTITION BY RANGE (timestamp);

-- Индексы для фильтрованных сделок
CREATE INDEX IF NOT EXISTS idx_filtered_trades_exchange_symbol ON filtered_trades(exchange, symbol);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_timestamp ON filtered_trades(timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_volume_usd ON filtered_trades(volume_usd DESC);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_category ON filtered_trades(trade_category);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_threshold ON filtered_trades(percentile_threshold);

-- 4. Таблица для статистики окон (скользящих окон)
CREATE TABLE IF NOT EXISTS volume_windows (
                                              id UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
    exchange VARCHAR(20) NOT NULL,
    symbol VARCHAR(20) NOT NULL,
    start_time BIGINT NOT NULL,
    end_time BIGINT NOT NULL,
    total_trades INTEGER NOT NULL,

    -- Статистика по объёмам
    min_volume DECIMAL(30, 8),
    max_volume DECIMAL(30, 8),
    avg_volume DECIMAL(30, 8),
    median_volume DECIMAL(30, 8),
    stddev_volume DECIMAL(30, 8),

    -- Перцентили
    p50_volume DECIMAL(30, 8), -- медиана
    p95_volume DECIMAL(30, 8),
    p98_volume DECIMAL(30, 8),
    p99_volume DECIMAL(30, 8),

    -- Пороги для фильтрации
    filter_percentile DECIMAL(5, 2) DEFAULT 0.98,
    filter_threshold DECIMAL(30, 8),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(exchange, symbol, start_time, end_time)
    );

-- Функция для автоматического создания партиций
CREATE OR REPLACE FUNCTION create_trade_partitions() RETURNS void AS $$
DECLARE
partition_name TEXT;
    start_date DATE;
    end_date DATE;
BEGIN
    -- Для raw_trades: дневные партиции
    start_date := CURRENT_DATE;
    end_date := start_date + INTERVAL '1 day';
    partition_name := 'raw_trades_' || to_char(start_date, 'YYYY_MM_DD');

EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I
        PARTITION OF raw_trades
        FOR VALUES FROM (%L) TO (%L)',
               partition_name,
               EXTRACT(EPOCH FROM start_date) * 1000,
               EXTRACT(EPOCH FROM end_date) * 1000
        );

-- Для filtered_trades: недельные партиции
start_date := DATE_TRUNC('week', CURRENT_DATE);
    end_date := start_date + INTERVAL '1 week';
    partition_name := 'filtered_trades_' || to_char(start_date, 'YYYY_MM_DD');

EXECUTE format('
        CREATE TABLE IF NOT EXISTS %I
        PARTITION OF filtered_trades
        FOR VALUES FROM (%L) TO (%L)',
               partition_name,
               EXTRACT(EPOCH FROM start_date) * 1000,
               EXTRACT(EPOCH FROM end_date) * 1000
        );

RAISE NOTICE 'Created partitions for %', CURRENT_DATE;
END;
$$ LANGUAGE plpgsql;

-- Функция для очистки старых raw_trades (храним только 1 миллион последних сделок)
CREATE OR REPLACE FUNCTION cleanup_old_raw_trades()
RETURNS INTEGER AS $$
DECLARE
total_count BIGINT;
    to_delete_count INTEGER;
    deleted_count INTEGER DEFAULT 0;
BEGIN
    -- Получаем общее количество записей
SELECT COUNT(*) INTO total_count FROM raw_trades;

-- Если больше 1.2 миллиона, удаляем старые
IF total_count > 1200000 THEN
        to_delete_count := total_count - 1000000;

        -- Удаляем самые старые записи
WITH deleted AS (
DELETE FROM raw_trades
WHERE id IN (
    SELECT id FROM raw_trades
    ORDER BY timestamp ASC
    LIMIT to_delete_count
    )
    RETURNING 1
    )
SELECT COUNT(*) INTO deleted_count FROM deleted;

RAISE NOTICE 'Deleted % old raw_trades', deleted_count;
END IF;

RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;