План из `critical-issues.md` уже покрывает самые острые архитектурные и производительные проблемы. Ниже — **дополнительные улучшения**, которые превратят сервис из «рабочего прототипа» в production-ready систему для финансовых потоков. Я разбил их по приоритетам и областям, с привязкой к вашему коду.

---

### 🔴 Must-Have (Критично для стабильности под нагрузкой)

| Область | Что добавить | Почему | Где в коде |
|--------|--------------|--------|------------|
| **Backpressure + DLQ** | Dead Letter Queue для «битых» JSON или упавших вставок в БД | Сейчас при `catch (e: Exception)` тик просто теряется. В трейдинге потеря даже 0.1% данных критична | `TradeProcessor.process()`, `BatchProcessor.flushBatch()` |
| **Circuit Breaker для БД** | Простой счётчик ошибок + таймаут возврата в режим работы | Если PostgreSQL замедлится или упадёт, корутины зависнут в каналах, OOM растёт | `TradeDAO.*`, `BatchProcessor`, `VolumeFilterProcessor` |
| **Валидация входящих данных** | Быстрая проверка структуры JSON до парсинга `Trade` | Биржи иногда присылают `ping`, `sub`-ответы или битые сообщения. `jacksonObjectMapper().readTree()` дорогой | `ExchangeClient.connectAndListen()`, `BaseExchangeAdapter` |
| **Partitioning таблиц** | PostgreSQL партиционирование по `timestamp` (месяц/неделя) | `raw_trades` и `filtered_trades` быстро вырастут в сотни GB. Индексы начнут тормозить, `VACUUM` съест IO | `sql/001_init_schema.sql`, `TradeDAO` |

---

### 🟡 High-Impact (Сильно упростят поддержку и отладку)

| Область | Что добавить | Почему | Где в коде |
|--------|--------------|--------|------------|
| **Micrometer + Prometheus** | Заменить кастомный `/metrics` на `io.micrometer.core` | Готовые метрики JVM, GC, HikariCP, корутин, размера каналов, latency | `MonitoringServer.kt`, `TradeProcessor.getMetrics()` |
| **Structured Logging** | JSON-логгер с `correlationId` на партию тиков | Упростит поиск инцидентов в ELK/Loki. Сейчас логи смешанные | `Main.kt`, `mu.KotlinLogging` → `net.logstash.logback` |
| **Dynamic Batch Sizing** | Адаптивный размер батча (например, 500 → 5000) на основе latency БД | Фиксированный `batchSize=1000` не оптимален при разной нагрузке | `BatchProcessor.kt`, `DatabaseConfig` |
| **Kotlinx-datetime** | Заменить `java.time.*` на `kotlinx-datetime` | Меньше аллокаций, нативная интеграция с корутинами и сериализацией | `Trade.kt`, `VolumeWindow.kt`, `AggregateCandle.kt` |

---

### 🟢 Nice-to-Have (Оптимизация и масштабирование)

| Область | Что добавить | Почему |
|--------|--------------|--------|
| **Object Pooling для `Trade`** | Использовать `kotlinx-io` pool или ring-buffer для объектов | В горячем пути создаются миллионы `Trade` и `BigDecimal`. Снижает GC pressure на 30-40% |
| **Zero-Copy WebSocket Frames** | Работать с `Frame.Text.readText()` через `Utf8` decoder без промежуточных `String` | Ktor уже предоставляет `Frame` API, можно парсить `InputStream` напрямую |
| **DI Container (Koin)** | Внедрить Koin для `TradeDAO`, `ConfigManager`, процессоров | Упростит тестирование, уберёт «передачу всего через конструкторы» |
| **Health/Readiness Probes** | Разделить `/health` (liveness) и `/ready` (readiness) для Kubernetes/Docker | `/ready` должен проверять: каналы не переполнены, БД доступна, WS подключены |

---

### 💡 Конкретные правки под ваш стек

1. **Вместо `ConcurrentHashMap<String, ConcurrentLinkedQueue<Trade>>` → `CoroutineChannel` + `MutableMap`**  
   Уже заложено в плане, но добавьте **механизм очистки каналов** при отключении инструмента, иначе `ConcurrentHashMap` будет расти бесконечно:
   ```kotlin
   // В BatchProcessor
   private val cleanupJob = scope.launch {
       while (isActive) {
           delay(5.minutes)
           tradeChannels.entries.removeAll { (_, ch) -> ch.isEmpty && ch.closedForReceive }
       }
   }
   ```

2. **PostgreSQL `ON CONFLICT` для `aggregates` и `volume_windows`**  
   У вас уже есть, но добавьте `WHERE excluded.total_ticks > aggregates.total_ticks`, чтобы перезаписывать только если прилетели более полные данные (защита от out-of-order пакетов).

3. **SQLite Disk Buffer**  
   В плане упомянут SQLite. Для embedded-буфера используйте `PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;`. Это даст ~10-20x ускорение записи без потери целостности при краше.

4. **t-digest + ApproxSMA интеграция**  
   Не смешивайте их в одном классе. `t-digest` — для перцентилей, `ApproxSMA` — для `mean/stddev`. Пример API:
   ```kotlin
   class VolumeWindowTracker(
       private val sma: ApproxSMA,
       private val digest: TDigest
   ) {
       fun ingest(volumeUsd: BigDecimal) {
           sma.add(volumeUsd)
           digest.add(volumeUsd.toDouble())
       }
       fun getSnapshot() = WindowStats(
           mean = sma.mean,
           stddev = sma.stddev,
           p95 = digest.quantile(0.95),
           p99 = digest.quantile(0.99)
        )
    }
    ```

---

### 📋 Сводка принятых решений

| # | Решение | Статус | Раздел в critical-issues.md |
|---|---------|--------|---------------------------|
| 1 | **Arrow → JSONB PostgreSQL** — отказ от Apache Arrow, хранение footprint-свечей в JSONB-колонке | ✅ Принято | [Раздел 2](critical-issues.md:58) |
| 2 | **t-digest как библиотека** — `com.tdunning:t-digest`, класс `TDigest`, без самописного аналога | ✅ Принято | [Раздел 3](critical-issues.md:102) |
| 3 | **ApproxSMA** — самописный класс для mean/stddev (не смешивать с t-digest) | ✅ Принято | [Раздел 3.1](critical-issues.md:106) |
| 4 | **Channel-based backpressure** — 3 независимых канала с `BufferOverflow.DROP_OLDEST` | ✅ **Выполнено** | [Раздел 1](critical-issues.md:14) |
| 5 | **Circuit Breaker для БД** — CLOSED → OPEN → HALF_OPEN, сброс в DiskBuffer при OPEN | ✅ Принято | [Раздел 4b, 8](critical-issues.md:169) |
| 6 | **DLQ + SQLite DiskBuffer** — Dead Letter Queue для битых сообщений + буфер на диск | ✅ **Выполнено** | [Раздел 6](critical-issues.md:223) |
| 7 | **Graceful shutdown** — правильная последовательность: WS → flush → snapshot → stop | ✅ Принято | [Раздел 5](critical-issues.md:200) |
| 8 | **Bybit временно отключён** — архитектура ExchangeAdapter сохранена | ✅ Принято | [Раздел 14](critical-issues.md:372) |
| 9 | **raw_trades опциональны** — можно удалить таблицу, перцентили из t-digest | ✅ Принято | [Раздел 15](critical-issues.md:380) |
| 10 | **Micrometer + Prometheus** — замена кастомного /metrics | 🟡 Средний | [Раздел 9](critical-issues.md:286) |
| 11 | **Dynamic Batch Sizing** — адаптивный размер батча по latency БД | 🟡 Средний | [Раздел 10](critical-issues.md:309) |
| 12 | **Structured Logging** — JSON-логгер для ELK/Loki | 🟢 Позже | [Раздел 13](critical-issues.md:358) |
| 13 | **Health/Readiness Probes** — /health и /ready для K8s | 🟢 Позже | [Раздел 12](critical-issues.md:344) |
| 14 | **Partitioning таблиц** — партиционирование raw_trades/filtered_trades по месяцам | ❓ Требует обсуждения | — |
| 15 | **Kotlinx-datetime** — замена java.time.* | ❓ Требует обсуждения | — |
| 16 | **Object Pooling / Zero-Copy / Koin** | ❓ Требует обсуждения | — |

---

### 🎯 Порядок внедрения

Все решения разбиты на 12 шагов в приоритетном порядке.  
Детальное описание каждого шага — в [`critical-issues.md#приоритетный-порядок-внедрения`](critical-issues.md:485).

**Ключевые принципы:**
1. Сначала исправляем **критические проблемы** (шаги 1-6) — потеря данных, race conditions, утечка памяти
2. Затем **улучшаем наблюдаемость** (шаги 7-8) — метрики, логи
3. После **оптимизируем** (шаги 9-10) — dynamic batch, CI/CD
4. В последнюю очередь **добавляем фичи** (шаги 11-12) — probes, structured logging

**Зависимости между шагами:**
- Шаг 1 (t-digest) → не зависит от других, можно начинать первым
- Шаг 2 (каналы) → базовый для шагов 3, 4, 5
- Шаг 3 (Circuit Breaker) → зависит от шага 2
- Шаг 4 (DLQ + DiskBuffer) → зависит от шага 2
- Шаг 6 (Graceful shutdown) → зависит от шагов 1-5 (нужны все flush + snapshot)