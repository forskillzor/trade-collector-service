# TradeCollectorService — Production Readiness Audit

**Дата:** 2026-05-29  
**Версия:** 2.0.0  
**Вывод:** Проект НЕ готов к продакшену. Выявлено 6 блокирующих (BLOCKER) проблем, 11 критических (CRITICAL) и 8 средних (MEDIUM).

---

## Сводная таблица

| # | Проблема | Серьёзность | Файл | Строка |
|---|----------|------------|------|--------|
| 1 | Пароль БД в открытом виде в репозитории | 🔴 BLOCKER | `config/production.json` | 20 |
| 2 | IP сервера в исходном коде | 🔴 BLOCKER | `config/AppConfig.kt` | 22 |
| 3 | Нет ни одного теста (unit/integration) | 🔴 BLOCKER | — | — |
| 4 | `Double` для цен/объёмов — потеря точности | 🔴 BLOCKER | `model/Trade.kt` | 8-9 |
| 5 | Data Race в `BatchProcessor.pending` | 🔴 BLOCKER | `service/BatchProcessor.kt` | 30 |
| 6 | Data Race в `VolumeFilterProcessor.slidingWindows` | 🔴 BLOCKER | `service/VolumeFilterProcessor.kt` | 21,39-45 |
| 7 | Resource leak: `ExchangeClient` создаёт бесконтрольные `CoroutineScope` | 🔴 CRITICAL | `service/ExchangeClient.kt` | 42 |
| 8 | Config.json загружается дважды при старте | 🔴 CRITICAL | `Main.kt` | 39, 50 |
| 9 | Apache Arrow — утечка off-heap памяти | 🔴 CRITICAL | `service/AggregateProcessor.kt` | 94 |
| 10 | `filtered_trades` — одиночные INSERT вместо batch | 🔴 CRITICAL | `service/VolumeFilterProcessor.kt` | 215 |
| 11 | Data Race в `TradeProcessor.InstrumentStats` | 🔴 CRITICAL | `service/TradeProcessor.kt` | 72-76 |
| 12 | DLQ и DiskBuffer используют один файл SQLite | 🔴 CRITICAL | `storage/DeadLetterQueue.kt:18`, `storage/DiskBuffer.kt:19` |
| 13 | Нет Circuit Breaker для PostgreSQL | 🔴 CRITICAL | — | — |
| 14 | Graceful shutdown без таймаутов | 🔴 CRITICAL | `Main.kt` | 92-94 |
| 15 | `MonitoringServer` слушает только localhost | 🔴 CRITICAL | `service/MonitoringServer.kt` | 34 |
| 16 | TradeProcessor логирует DEBUG каждые 1000 тиков | 🔴 CRITICAL | `service/TradeProcessor.kt` | 213-220 |
| 17 | HikariCP без leak-detection и validation timeout | 🔴 CRITICAL | `storage/postgres/TradeDAO.kt` | 21-37 |
| 18 | t-digest библиотека есть, но не используется | 🟡 MEDIUM | `service/VolumeFilterProcessor.kt` | 93 |
| 19 | Exposed ORM + kotlin-csv — неиспользуемые зависимости | 🟡 MEDIUM | `build.gradle.kts` | 66-69,86 |
| 20 | Export-функциональность не реализована | 🟡 MEDIUM | — | — |
| 21 | `filtered_trades` PARTITION BY RANGE без реальных партиций | 🟡 MEDIUM | `sql/001_init_schema.sql` | 84 |
| 22 | `/health` не проверяет БД и WebSocket | 🟡 MEDIUM | `service/MonitoringServer.kt` | 55-63 |
| 23 | Нет Prometheus/Micrometer метрик | 🟡 MEDIUM | `service/MonitoringServer.kt` | 66-68 |
| 24 | CI/CD: пароль в SSH heredoc | 🟡 MEDIUM | `.github/workflows/deploy.yml` | 73 |
| 25 | Нет structured logging (JSON) для ELK | 🟡 MEDIUM | `resources/logback.xml` | — |

---

## Детальный разбор

### 🔴 BLOCKER #1: Пароль БД в репозитории

**Файл:** `config/production.json:20`
```json
"password": "JXDsdSZzXx1221!!!"
```

**Риск:** Любой, кто получит доступ к репозиторию (включая CI/CD логи), видит боевой пароль PostgreSQL.

**Исправление:**
1. Немедленно сменить пароль на сервере
2. Удалить пароль из `config/production.json`
3. Передать пароль только через `DB_PASSWORD` env var (механизм `resolvedPassword` уже готов в `DatabaseConfig`)
4. Убедиться что `.deploy.env` в `.gitignore`
5. Добавить `config/production.json` в `.gitignore` (или выпилить из него все secrets)

---

### 🔴 BLOCKER #2: IP сервера в исходном коде

**Файл:** `config/AppConfig.kt:22`
```kotlin
val host: String = "95.81.99.28",
```

**Риск:** Раскрытие инфраструктуры.

**Исправление:** Убрать значение по умолчанию. Оставить `localhost` или `""`, требовать `DB_HOST` env var в production.

---

### 🔴 BLOCKER #3: Нет тестов

В проекте **ноль тестовых файлов**. `build.gradle.kts:95-97` объявляет `tasks.test { useJUnitPlatform() }`, но `src/test/` не существует.

**Риск:** Любое изменение может сломать продуктовый пайплайн без обнаружения.

**Минимальный набор тестов для продакшена:**
- `ExchangeAdapter` — unit-тесты на парсинг JSON (Binance/Bybit)
- `TradeProcessor.processInternal()` — unit-тест диспетчеризации
- `VolumeFilterProcessor.recalculateWindowStats()` — unit-тест перцентилей
- `TradeDAO.insertRawTradesBatch()` — интеграционный тест с Testcontainers
- `DeadLetterQueue` — unit-тест retry-логики
- `DiskBuffer` — unit-тест WAL/SQLite

---

### 🔴 BLOCKER #4: `Double` для цен и объёмов

**Файл:** `model/Trade.kt:8-9`
```kotlin
val price: Double,
val quantity: Double,
```

**Риск:** Потеря точности для финансовых данных. `Double` — это IEEE 754 binary64 (53 бита мантиссы). Для пары BTCUSDT (~$100,000) с тиком 0.01 это даёт относительную погрешность ~1e-15, но при накоплении в окне из 1M сделок ошибка аккумулируется. Для альткоинов с ценой <$1 и объёмами в миллионах — ещё хуже.

**Исправление:** Заменить все `Double` на `BigDecimal` (или `kotlinx-serialization` `Decimal`). Метод `getVolumeUsd()` на `Double` — тоже проблема. Arrow-парсинг всё равно конвертирует в `Double` — ещё один аргумент к отказу от Arrow.

---

### 🔴 BLOCKER #5: Data Race в `BatchProcessor`

**Файл:** `service/BatchProcessor.kt:30`
```kotlin
suspend fun consume(channel: Channel<Trade>) {
    val pending = mutableMapOf<String, MutableList<Trade>>()
```

**Проблема:** `MutableMap` и `MutableList` — не потокобезопасные структуры. Сейчас consumer один, но `flushAll()` вызывается по таймеру из той же корутины — OK. Однако если в будущем добавится второй consumer, будет гонка. Уже сейчас есть риск: `consume()` и таймерный `flushAll()` работают в одном потоке корутины, но `MutableList.add()` и `MutableList.clear()` внутри `flushBatch()` не атомарны относительно итерации `forEach` в `flushAll()`.

**Исправление:** Заменить на `ConcurrentHashMap` + синхронизировать доступ, либо перейти на actor-модель через отдельный Channel для flush-команд.

---

### 🔴 BLOCKER #6: Data Race в `VolumeFilterProcessor`

**Файл:** `service/VolumeFilterProcessor.kt:21,39-45`
```kotlin
private val slidingWindows = ConcurrentHashMap<String, SlidingWindowStats>()
// ...
data class SlidingWindowStats(
    var volumes: LinkedList<BigDecimal> = LinkedList(),
    var sortedVolumes: MutableList<BigDecimal> = mutableListOf(),
    var totalTrades: Int = 0,
    // ...
)
```

**Проблема:** `ConcurrentHashMap` защищает только операции с самой мапой (put/get). Поля внутри `SlidingWindowStats` — `var` с изменяемыми коллекциями (`LinkedList`, `MutableList`), модифицируются без синхронизации. При одновременном `processTrade()` (пишет в `volumes`) и `recalculateWindowStats()` (сортирует `sortedVolumes`) происходит гонка.

**Исправление:**
1. Сделать `SlidingWindowStats` иммутабельным data class
2. Обернуть каждое окно в `Mutex` или использовать actor-модель (по одному actor'у на инструмент)
3. Либо перейти на однопоточную обработку через Channel без shared mutable state

---

### 🔴 CRITICAL #7: Resource leak в `ExchangeClient`

**Файл:** `service/ExchangeClient.kt:42`
```kotlin
val job = CoroutineScope(Dispatchers.IO).launch {
    connectAndListen(url, symbol, client)
}
```

**Проблема:** Для каждого symbol создаётся новый `CoroutineScope(Dispatchers.IO)`. При остановке сервиса вызывается `clientJobs.forEach { it.cancel() }`, но сам `CoroutineScope` не отменяется — остаётся висеть. За 10 символов это 10 утекших scope'ов + HttpClient'ов.

**Исправление:** Использовать единый `coroutineScope` для всех клиентов (передавать из `TradeCollectorService`), либо хранить scope и отменять его при stop.

---

### 🔴 CRITICAL #8: Config загружается дважды

**Файл:** `Main.kt:39,50`
```kotlin
val configLoaded = ConfigManager.loadFromFile() // загрузка 1 — без аргумента
// ...
ConfigManager.loadFromFile(configPath)            // загрузка 2 — с аргументом
```

**Проблема:** Первый вызов без пути ищет config.json по 6 разным путям, второй — только `"config.json"`. Если первый нашёл, второй может не найти — тогда `config.exchanges` будет пустым списком (дефолтное значение `emptyList()`), и сервис запустится без бирж.

**Исправление:** Убрать дублирующийся вызов. Оставить один: `ConfigManager.loadFromFile("config.json")`.

---

### 🔴 CRITICAL #9: Apache Arrow — утечка off-heap памяти

**Файл:** `service/AggregateProcessor.kt:94`
```kotlin
val childAllocator = this@AggregateProcessor.allocator.newChildAllocator("candle-builder", 0, Long.MAX_VALUE)
```

**Проблема:** Каждый вызов `buildArrowData()` создаёт новый `childAllocator`. Хотя он закрывается в `finally`, сам `RootAllocator` не имеет ограничения памяти и не освобождает native-память обратно ОС (только в пул). При длительной работе сервиса off-heap память растёт бесконечно.

**Исправление:** Отказаться от Arrow полностью в пользу JSONB в PostgreSQL (как запланировано в `critical-issues.md` раздел 2).

---

### 🔴 CRITICAL #10: Одиночные INSERT для filtered_trades

**Файл:** `service/VolumeFilterProcessor.kt:215`
```kotlin
dao.insertFilteredTrade(filteredTrade)  // одиночная вставка!
```

**Проблема:** Каждая «китовая» сделка вставляется отдельным SQL-запросом. При большом количестве инструментов (>10) и низком пороге перцентиля это создаёт огромную нагрузку на БД.

**Исправление:** Аккумулировать `FilteredTrade` в буфер и вставлять batch'ами (`insertFilteredTradesBatch` уже реализован в DAO).

---

### 🔴 CRITICAL #11: Data Race в `TradeProcessor.InstrumentStats`

**Файл:** `service/TradeProcessor.kt:72-76,69`
```kotlin
data class InstrumentStats(
    var totalTrades: Long = 0,
    var lastTradeTime: Long = 0,
    var batchQueueSize: Int = 0
)
private val instrumentStats = ConcurrentHashMap<String, InstrumentStats>()
```

**Проблема:** Поля `var` внутри data class не защищены `ConcurrentHashMap`. Несколько корутин могут одновременно писать в `totalTrades++`, что не атомарно для `Long`.

**Исправление:** Использовать `AtomicLong`/`AtomicInteger`:
```kotlin
data class InstrumentStats(
    val totalTrades: AtomicLong = AtomicLong(0),
    val lastTradeTime: AtomicLong = AtomicLong(0),
    val batchQueueSize: AtomicInteger = AtomicInteger(0)
)
```

---

### 🔴 CRITICAL #12: DLQ и DiskBuffer — одна БД

**Файлы:** `storage/DeadLetterQueue.kt:18`, `storage/DiskBuffer.kt:19`
```kotlin
class DeadLetterQueue(private val dbPath: String = "./data/trade_buffer.db")
class DiskBuffer(private val dbPath: String = "./data/trade_buffer.db")
```

**Проблема:** Оба компонента по умолчанию используют один и тот же файл SQLite. DLQ вызывает `ensureTable()` (создаёт таблицу `dlq`), DiskBuffer — `ensureTable()` (создаёт таблицу `buffer_trades`). WAL-режим от DiskBuffer распространяется на весь файл. При высокой нагрузке DiskBuffer может заблокировать DLQ-записи и наоборот.

**Исправление:** Разделить файлы: `./data/disk_buffer.db` и `./data/dlq.db`.

---

### 🔴 CRITICAL #13: Нет Circuit Breaker для PostgreSQL

При падении PostgreSQL сервис продолжает пытаться писать в БД без ограничений. Это ведёт к:
- Росту `DiskBuffer` до заполнения диска
- Бесконечному retry в `BatchProcessor.flushBatch()` (batch не очищается)
- OOM при переполнении каналов

**Исправление:** Реализовать Circuit Breaker с состояниями CLOSED → OPEN → HALF_OPEN (описан в `critical-issues.md`, разделы 4b и 8).

---

### 🔴 CRITICAL #14: Graceful shutdown без таймаутов

**Файл:** `Main.kt:92-94`
```kotlin
val shutdownHook = Thread {
    runBlocking {
        service.stop()
        dao.shutdown()
    }
}
```

**Проблема:** `service.stop()` вызывает `tradeProcessor?.shutdown()`, который делает `aggregateProcessor.flushAll()` — синхронная операция без таймаута. При большом количестве незакрытых свечей это может занять минуты. SIGTERM от systemd (по умолчанию 90 секунд) убьёт процесс, не дождавшись завершения.

**Исправление:** Обернуть каждую стадию shutdown в `withTimeout(30.seconds)`.

---

### 🔴 CRITICAL #15: MonitoringServer только на localhost

**Файл:** `service/MonitoringServer.kt:34`
```kotlin
val engine = embeddedServer(Jetty, port = port, host = "localhost") {
```

**Риск:** В Docker/K8s healthcheck и Prometheus не смогут достучаться до сервиса.

**Исправление:** Сделать host конфигурируемым (`0.0.0.0` для контейнеров).

---

### 🔴 CRITICAL #16: DEBUG-логирование в production

**Файл:** `resources/logback.xml:30`
```xml
<logger name="com.aandios.service.TradeProcessor" level="DEBUG" />
```

И `TradeProcessor.kt:213-220` пишет лог каждые 1000 тиков на уровне DEBUG. При 10K TPS это сотни строк в секунду — забивает диск и маскирует важные сообщения.

**Исправление:** Поднять до INFO/WARN для production-окружения. Использовать `log.trace` для детальных логов.

---

### 🔴 CRITICAL #17: HikariCP без мониторинга утечек

**Файл:** `storage/postgres/TradeDAO.kt:21-37`

HikariCP настроен без:
- `leakDetectionThreshold` — не обнаружит утечку соединений
- `connectionTestQuery` или `validationTimeout` — не проверит живость соединения перед выдачей
- `keepaliveTime` — соединения могут умирать при простое

**Исправление:**
```kotlin
leakDetectionThreshold = 2000  // 2 секунды
keepaliveTime = 300000         // 5 минут
connectionTestQuery = "SELECT 1"
```

---

### 🟡 MEDIUM #18: t-digest не используется

**Файл:** `service/VolumeFilterProcessor.kt:93`

Библиотека `com.tdunning:t-digest:3.3` добавлена в `build.gradle.kts:89`, но `VolumeFilterProcessor` до сих пор использует сортированный список (`sortedVolumes`) для расчёта перцентилей — O(n log n) на каждый пересчёт окна. t-digest даёт O(log n) и константную память.

---

### 🟡 MEDIUM #19: Неиспользуемые зависимости

- `org.jetbrains.exposed:*` (4 модуля) — заявлены, но нигде не используются. Весь доступ к БД — raw JDBC.
- `com.github.doyaaaaaken:kotlin-csv-jvm:1.9.2` — не используется.

**Исправление:** Удалить неиспользуемые зависимости из `build.gradle.kts`.

---

### 🟡 MEDIUM #20: Export не реализован

`ExportConfig` описан, параметры заданы, но ни одного класса для экспорта нет. Либо реализовать, либо убрать из конфигурации.

---

### 🟡 MEDIUM #21: Партиционирование без партиций

**Файл:** `sql/001_init_schema.sql:84`
```sql
) PARTITION BY RANGE (timestamp);
```

Таблица `filtered_trades` объявлена как партиционированная, но ни одной партиции не создано. Запросы к ней будут работать, но все данные попадают в дефолтную партицию — преимуществ партиционирования нет.

**Исправление:** Создать партиции по месяцам, либо убрать `PARTITION BY RANGE`.

---

### 🟡 MEDIUM #22: `/health` не проверяет зависимости

**Файл:** `service/MonitoringServer.kt:55-63`

Health check всегда возвращает `"healthy"`, не проверяя доступность PostgreSQL и состояние WebSocket-соединений. Непригоден для Kubernetes readiness probe.

**Исправление:** Добавить проверку БД (`SELECT 1`) и статуса ExchangeClient'ов.

---

### 🟡 MEDIUM #23: Кастомные метрики вместо Prometheus

`/metrics` возвращает `Map<String, Any>` в JSON. Для production-мониторинга нужен формат Prometheus (`text/plain`). Лучше использовать Micrometer.

---

### 🟡 MEDIUM #24: CI/CD — пароль в SSH

**Файл:** `.github/workflows/deploy.yml` — пароль передаётся через heredoc, виден в логах GitHub Actions.

---

### 🟡 MEDIUM #25: Нет structured logging

Для ELK/Loki/Grafana нужны JSON-логи. Сейчас — plain text через Logback.

---

## Приоритетный план исправлений

### Фаза 1 — Блокирующие (неделя 1)
| # | Задача | Оценка |
|---|--------|--------|
| 1.1 | Удалить пароль из `production.json`, сменить пароль на сервере | 30 мин |
| 1.2 | Убрать IP из `AppConfig.kt` | 10 мин |
| 1.3 | Заменить `Double` на `BigDecimal` в `Trade` | 2 ч |
| 1.4 | Исправить data race в `BatchProcessor` | 2 ч |
| 1.5 | Исправить data race в `VolumeFilterProcessor` | 3 ч |
| 1.6 | Написать минимальный набор тестов (5 unit + 1 integration) | 8 ч |

### Фаза 2 — Критические (неделя 2)
| # | Задача | Оценка |
|---|--------|--------|
| 2.1 | Исправить resource leak в `ExchangeClient` | 2 ч |
| 2.2 | Убрать двойную загрузку конфига в `Main.kt` | 15 мин |
| 2.3 | Перевести `AggregateProcessor` с Arrow на JSONB | 8 ч |
| 2.4 | Batch-вставка `filtered_trades` | 2 ч |
| 2.5 | Atomic-поля в `InstrumentStats` | 30 мин |
| 2.6 | Разные SQLite-файлы для DLQ и DiskBuffer | 15 мин |
| 2.7 | Circuit Breaker для БД | 4 ч |
| 2.8 | Таймауты в graceful shutdown | 1 ч |
| 2.9 | `0.0.0.0` для MonitoringServer | 15 мин |
| 2.10 | DEBUG → INFO для TradeProcessor в production | 10 мин |
| 2.11 | HikariCP leak detection + keepalive | 15 мин |

### Фаза 3 — Средние (неделя 3)
| # | Задача | Оценка |
|---|--------|--------|
| 3.1 | Интегрировать t-digest в VolumeFilterProcessor | 4 ч |
| 3.2 | Удалить неиспользуемые зависимости | 15 мин |
| 3.3 | Реализовать или удалить Export | 4 ч |
| 3.4 | Создать партиции для `filtered_trades` | 1 ч |
| 3.5 | `/health` с проверкой БД и WebSocket | 1 ч |
| 3.6 | Prometheus-метрики через Micrometer | 4 ч |
| 3.7 | JSON structured logging | 2 ч |
| 3.8 | CI/CD — безопасная передача secrets | 30 мин |

---

## Дополнительные рекомендации

1. **Контейнеризация:** Проверить, что Dockerfile (`docker/Dockerfile`) собирается. Сейчас указан GraalVM native-image — это долгий билд (~10 мин). Для итеративной разработки лучше использовать JRE-образ.

2. **Лимиты JVM:** Добавить `-Xmx` и `-Xms` в `run.sh` и systemd-сервис. Без этого сервис может занять всю память VPS.

3. **Метрики HikariCP:** Включить JMX-метрики пула (`registerMbeans = true`).

4. **SQL-инъекции:** Все SQL-запросы используют `PreparedStatement` с `?` — безопасно.

5. **Версионирование схемы БД:** Сейчас один SQL-файл `001_init_schema.sql`. Нужен механизм миграций (Flyway/Liquibase) для будущих изменений схемы.

6. **Graceful degradation:** При отключении символа на бирже сервис должен корректно обрабатывать это без перезапуска.

---

## Итог

| Категория | Количество |
|-----------|-----------|
| 🔴 BLOCKER | 6 |
| 🔴 CRITICAL | 11 |
| 🟡 MEDIUM | 8 |
| **Всего** | **25** |

**Оценка трудозатрат:** ~60 человеко-часов (2 недели для одного разработчика).
**Текущий статус:** Проект на стадии рабочего прототипа. После исправления BLOCKER + CRITICAL проблем готов к staging-окружению. После MEDIUM — к production.
