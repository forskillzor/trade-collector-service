CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DROP TABLE IF EXISTS raw_trades CASCADE;
-- 1. Таблица для сырых сделок (raw_trades)
CREATE TABLE IF NOT EXISTS raw_trades
(
    id         BIGSERIAL      PRIMARY KEY ,
    exchange   VARCHAR(20)    NOT NULL,
    symbol     VARCHAR(20)    NOT NULL,
    timestamp  BIGINT         NOT NULL,
    price      DECIMAL(20, 8) NOT NULL,
    quantity   DECIMAL(30, 8) NOT NULL,
    is_buy     BOOLEAN        NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Индексы для быстрого поиска
CREATE INDEX idx_raw_trades_exchange_symbol ON raw_trades(exchange, symbol);
CREATE INDEX idx_raw_trades_timestamp ON raw_trades(timestamp DESC);
CREATE INDEX idx_raw_trades_exchange_symbol_time ON raw_trades(exchange, symbol, timestamp DESC);
-- 2. Таблица для агрегированных данных (свечи по ценам)
CREATE TABLE IF NOT EXISTS aggregates
(
    id               UUID        DEFAULT uuid_generate_v4() PRIMARY KEY,
    exchange         VARCHAR(20)    NOT NULL,
    symbol           VARCHAR(20)    NOT NULL,
    timeframe        VARCHAR(10)    NOT NULL CHECK (timeframe IN ('1m', '5m', '15m', '30m', '1h', '4h', '1d')),

    -- Временной диапазон
    start_time       BIGINT         NOT NULL,
    end_time         BIGINT         NOT NULL,

    -- Файл Arrow с данными
    arrow_file_path  TEXT           NOT NULL,
    arrow_file_size  BIGINT         NOT NULL,
    compression_type VARCHAR(20) DEFAULT 'zstd',

    -- Метаданные о файле
    total_ticks      BIGINT         NOT NULL,
    min_price        DECIMAL(20, 8) NOT NULL,
    max_price        DECIMAL(20, 8) NOT NULL,
    price_levels     INTEGER        NOT NULL, -- количество уникальных цен в свече

-- Технические поля
    created_at       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   DEFAULT CURRENT_TIMESTAMP,

    -- Уникальность по времени и инструменту
    UNIQUE (exchange, symbol, timeframe, start_time, end_time)
);

-- Индексы для агрегатов
CREATE INDEX IF NOT EXISTS idx_aggregates_exchange_symbol ON aggregates (exchange, symbol);
CREATE INDEX IF NOT EXISTS idx_aggregates_timeframe ON aggregates (timeframe);
CREATE INDEX IF NOT EXISTS idx_aggregates_time_range ON aggregates (start_time, end_time);

-- 3. Таблица для фильтрованных сделок (big trades)
CREATE TABLE IF NOT EXISTS filtered_trades
(
    id                   BIGSERIAL      PRIMARY KEY,
    exchange             VARCHAR(20)    NOT NULL,
    symbol               VARCHAR(20)    NOT NULL,
    timestamp            BIGINT         NOT NULL,
    price                DECIMAL(20, 8) NOT NULL,
    quantity             DECIMAL(30, 8) NOT NULL,
    is_buy               BOOLEAN        NOT NULL,

    -- Фильтровочные метрики
    volume_usd           DECIMAL(30, 2) NOT NULL,
    percentile_threshold DECIMAL(5, 2)  NOT NULL, -- например 0.98 (98%)
    volume_threshold     DECIMAL(30, 8) NOT NULL, -- пороговый объём на момент сделки
    trade_category       VARCHAR(20),             -- 'large', 'very_large', 'whale'

-- Ссылка на окно выборки
    window_start_time    BIGINT         NOT NULL,
    window_end_time      BIGINT         NOT NULL,
    window_total_trades  INTEGER        NOT NULL,

    -- Технические поля
    created_at           TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    batch_id             UUID

) PARTITION BY RANGE (timestamp);

-- Индексы для фильтрованных сделок
CREATE INDEX IF NOT EXISTS idx_filtered_trades_exchange_symbol ON filtered_trades (exchange, symbol);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_timestamp ON filtered_trades (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_volume_usd ON filtered_trades (volume_usd DESC);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_category ON filtered_trades (trade_category);
CREATE INDEX IF NOT EXISTS idx_filtered_trades_threshold ON filtered_trades (percentile_threshold);

-- 4. Таблица для статистики окон (скользящих окон)
CREATE TABLE IF NOT EXISTS volume_windows
(
    id                UUID          DEFAULT uuid_generate_v4() PRIMARY KEY,
    exchange          VARCHAR(20) NOT NULL,
    symbol            VARCHAR(20) NOT NULL,
    start_time        BIGINT      NOT NULL,
    end_time          BIGINT      NOT NULL,
    total_trades      INTEGER     NOT NULL,

    -- Статистика по объёмам
    min_volume        DECIMAL(30, 8),
    max_volume        DECIMAL(30, 8),
    avg_volume        DECIMAL(30, 8),
    median_volume     DECIMAL(30, 8),
    stddev_volume     DECIMAL(30, 8),

    -- Перцентили
    p50_volume        DECIMAL(30, 8), -- медиана
    p95_volume        DECIMAL(30, 8),
    p98_volume        DECIMAL(30, 8),
    p99_volume        DECIMAL(30, 8),

    -- Пороги для фильтрации
    filter_percentile DECIMAL(5, 2) DEFAULT 0.98,
    filter_threshold  DECIMAL(30, 8),

    created_at        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP,

    UNIQUE (exchange, symbol, start_time, end_time)
);


-- Функция для очистки старых raw_trades (храним только 1 миллион последних сделок)
CREATE OR REPLACE FUNCTION cleanup_old_raw_trades()
    RETURNS INTEGER AS
$$
DECLARE
    total_count     BIGINT;
    to_delete_count INTEGER;
    deleted_count   INTEGER DEFAULT 0;
BEGIN
    -- Получаем общее количество записей
    SELECT COUNT(*) INTO total_count FROM raw_trades;

-- Если больше 1.2 миллиона, удаляем старые
    IF total_count > 1200000 THEN
        to_delete_count := total_count - 1000000;

        -- Удаляем самые старые записи
        WITH deleted AS (
            DELETE FROM raw_trades
                WHERE id IN (SELECT id
                             FROM raw_trades
                             ORDER BY timestamp ASC
                             LIMIT to_delete_count)
                RETURNING 1)
        SELECT COUNT(*)
        INTO deleted_count
        FROM deleted;

        RAISE NOTICE 'Deleted % old raw_trades', deleted_count;
    END IF;

    RETURN deleted_count;
END;
$$ LANGUAGE plpgsql;

-- Дополнительные индексы для JOIN запросов
CREATE INDEX IF NOT EXISTS idx_filtered_trades_window_time
    ON filtered_trades(window_start_time, window_end_time);

CREATE INDEX IF NOT EXISTS idx_volume_windows_time_range
    ON volume_windows(start_time, end_time);

-- Для быстрого поиска по времени в raw_trades
CREATE INDEX IF NOT EXISTS idx_raw_trades_exchange_symbol_time
    ON raw_trades(exchange, symbol, timestamp DESC);