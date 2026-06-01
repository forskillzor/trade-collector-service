# TradeCollectorService — Аудит готовности к продакшену

**Дата:** 2026-05-30  
**Версия кода:** 2.0.0 (commit `85c94aa cleanup logs`)  
**Вывод:** Проект готов к запуску в production с оговорками. 0 блокирующих, 0 критических, 7 средних.

---

## Сводная таблица

| # | Проблема | Серьёзность | Файл | Строка |
|---|----------|------------|------|--------|
| 1 | ~~Пароль БД в открытом виде в репозитории~~ ✅ | RESOLVED | `config/production.json` | 15 |
| 2 | ~~IP сервера в исходном коде~~ ✅ | RESOLVED | `config/AppConfig.kt` | 21 |
| 3 | ~~Нет ни одного теста~~ → тесты есть, но неполные | 🟡 MEDIUM | `src/test/` | — |
| 4 | ~~`Double` для цен/объёмов — потеря точности~~ ✅ | RESOLVED | `model/Trade.kt` | 8-9 |
| 5 | ~~Data Race в `BatchProcessor.flushBatch()`~~ ✅ | RESOLVED | `service/BatchProcessor.kt` | 37, 86 |
| 6 | ~~Data Race в `VolumeFilterProcessor.SlidingWindowStats`~~ ✅ | RESOLVED | `service/VolumeFilterProcessor.kt` | 21, 29-37 |
| 7 | ~~Config.json загружается дважды при старте~~ ✅ | RESOLVED | `Main.kt` | 27, 37 |
| 8 | ~~Resource leak: `ExchangeClient` создаёт `CoroutineScope` на символ~~ ✅ | RESOLVED | `service/ExchangeClient.kt` | 42 |
| 9 | ~~Apache Arrow — утечка off-heap памяти~~ ✅ | RESOLVED | `service/AggregateProcessor.kt` | — |
| 10 | ~~`filtered_trades` — одиночные INSERT вместо batch~~ ✅ | RESOLVED | `service/VolumeFilterProcessor.kt` | 202 |
| 11 | ~~Data Race в `TradeProcessor.InstrumentStats`~~ ✅ | RESOLVED | `service/TradeProcessor.kt` | 37-41 |
| 12 | ~~Нет Circuit Breaker для PostgreSQL~~ ✅ | RESOLVED | `service/BatchProcessor.kt` | 12-61 |
| 13 | ~~Graceful shutdown без таймаутов~~ ✅ | RESOLVED | `service/ShutdownChain.kt` | — |
| 14 | ~~`MonitoringServer` слушает только localhost~~ ✅ | RESOLVED | `service/MonitoringServer.kt` | 22 |
| 15 | ~~HikariCP без leak-detection и keepalive~~ ✅ | RESOLVED | `storage/postgres/TradeDAO.kt` | 35-37 |
| 16 | t-digest добавлен, но не используется | 🟡 MEDIUM | `service/VolumeFilterProcessor.kt` | 93 |
| 17 | Exposed ORM + kotlin-csv — неиспользуемые зависимости | 🟡 MEDIUM | `build.gradle.kts` | 66-69, 86 |
| 18 | Export-функциональность не реализована | 🟡 MEDIUM | — | — |
| 19 | `filtered_trades` PARTITION BY RANGE без реальных партиций | 🟡 MEDIUM | `sql/001_init_schema.sql` | 84 |
| 20 | `/health` не проверяет БД и WebSocket | 🟡 MEDIUM | `service/MonitoringServer.kt` | 55-63 |
| 21 | Нет DeadLetterQueue / DiskBuffer — потеря данных при сбоях | 🟡 MEDIUM | — | — |

---

## Детальный разбор

### ~~BLOCKER #1: Пароль БД в репозитории~~ ✅ RESOLVED

**Файл:** `config/production.json:15`
```json
"password": null
```

**Исправлено:** Пароль заменён на `null` в `config/production.json` и `config/config.example.json`. Боевой пароль передаётся только через `DB_PASSWORD` env var (через `/etc/default/trade-collector` на VPS). Механизм `resolvedPassword()` в `AppConfig.kt:42` уже готов.

**⚠️ Осталось:** сменить реальный пароль на VPS (он сохранился в git-истории старых коммитов).

---

### ~~BLOCKER #2: IP сервера в исходном коде~~ ✅ RESOLVED

**Файл:** `config/AppConfig.kt:21`
```kotlin
val host: String = "localhost",
```

**Исправлено:** Значение по умолчанию заменено на `localhost`. Реальный хост передаётся через `DB_HOST` env var. Механизм `resolvedHost()` (строка 30) читает `System.getenv("DB_HOST")`.

---

### ~~BLOCKER #3: Нет тестов~~ → MEDIUM: тесты есть, но неполные

Сейчас: **8 тестовых файлов, ~56 тестов**, `./gradlew test` — SUCCESS.

| Файл | Тестов | Что покрыто |
|------|--------|------------|
| `BinanceAdapterTest.kt` | 6 | parseTrade (happy-path, malformed, missing fields), isTradeMessage |
| `BybitAdapterTest.kt` | 9 | parseTrade (buy/sell, topic mismatch, empty data, missing fields), isTradeMessage, getWebSocketUrl |
| `BatchProcessorTest.kt` | 7 | enqueue, multi-instrument, batchSize flush, DAO failure re-queue, flush-on-stop, queue removal |
| `VolumeFilterProcessorTest.kt` | 7 | sliding window, window size limit, slideStep boundary, filtered trade threshold, getStats |
| `AggregateProcessorTest.kt` | 10 | calculateCandleStart/EndTime for all timeframes, epoch edge cases |
| `AggregateCandleBuilderTest.kt` | 9 | min/max price, bid/ask separation, JSON serialization, sorting, empty builder |
| `TradeTest.kt` | 5 | getVolumeUsd, fromRaw(BigDecimal), toLocalDateTime, isBuy |
| `FilteredTradeTest.kt` | 3 | TradeCategory enum, constructor with/without category |

**Осталось:**
- `recalculateWindowStats()` — не проверена числовая корректность перцентилей (p50/p95/p98/p99, stddev)
- `TradeDAO.insertRawTradesBatch()` — нет интеграционного теста с Testcontainers
- `BatchProcessor` — нет конкурентного теста (много продюсеров + таймер flush)

---

### ~~BLOCKER #4: `Double` для цен и объёмов~~ ✅ RESOLVED

**Файл:** `model/Trade.kt:12-13`, адаптеры, DAO, процессоры

**Исправлено:**
- `Trade.price`, `Trade.quantity` — `Double` → `BigDecimal`
- `Trade.getVolumeUsd()` — возвращает `BigDecimal` через `price.multiply(quantity)`
- `Trade.fromRaw()` — больше не конвертит в `.toDouble()`, сохраняет оригинальную точность
- Адаптеры (Binance, Bybit) — парсят JSON как `BigDecimal(node["p"].asText())` вместо `asDouble()`
- `TradeDAO` — убраны избыточные `BigDecimal.valueOf(trade.price)` (теперь передаётся напрямую)
- `AggregateProcessor`, `VolumeFilterProcessor`, `TradeProcessor` — убраны конвертации, логи форматируют BigDecimal напрямую
- Все тесты (TradeTest, AdapterTests, BatchProcessorTest, VolumeFilterProcessorTest, AggregateCandleBuilderTest, FilteredTradeTest) обновлены

---

### ~~BLOCKER #5: Data Race в `BatchProcessor`~~ ✅ RESOLVED

**Файл:** `service/BatchProcessor.kt:60`

**Исправлено:** `flushBatch()` обёрнут в `synchronized(tradeQueues) { ... }`. Все вызовы (из `addTrade()` и `processBatchLoop()`) теперь сериализованы — исключена гонка на `poll()`/`remove()`.

---

### ~~BLOCKER #6: Data Race в `VolumeFilterProcessor`~~ ✅ RESOLVED

**Файл:** `service/VolumeFilterProcessor.kt:22, 36`

**Исправлено:** Добавлен per-instrument lock через `ConcurrentHashMap<String, Any>`. Весь `processTrade()` обёрнут в `synchronized(lock) { ... }`. Гонка на `var`-полях и изменяемых коллекциях внутри `SlidingWindowStats` устранена.

---

### ~~CRITICAL #7: Config загружается дважды~~ ✅ RESOLVED

**Файл:** `Main.kt:27`

**Исправлено:** Убран дублирующий вызов `ConfigManager.loadFromFile(configPath)` (строка 37). Остался только один вызов с поиском по 6 путям, результат проверяется, после чего сразу `getConfig()`.

---

### ~~CRITICAL #8: Resource leak в `ExchangeClient`~~ ✅ RESOLVED

**Файл:** `service/ExchangeClient.kt:42` → `service/ExchangeClient.kt:20, 23, 43-47, 106-108`

**Было:** Для каждого symbol создавался новый `CoroutineScope(Dispatchers.IO)`. При `stop()` отменялись только Job'ы, но scope висел в памяти. При N символах = N утекших scope'ов.

**Исправлено:**
- `clientJobs: MutableList<Job>` заменён на `clientScope: CoroutineScope?`
- В `start()` создаётся один общий `CoroutineScope(Dispatchers.IO + SupervisorJob())`
- Все `launch` идут через `clientScope?.let { it.launch { ... } }`
- `stop()` вызывает `clientScope?.cancel()` + `clientScope = null`
- `SupervisorJob()` гарантирует, что падение одного symbol не отменяет остальные

---

### ~~CRITICAL #9: Apache Arrow — утечка off-heap памяти~~ ✅ RESOLVED

**Файл:** `service/AggregateProcessor.kt` — Arrow полностью удалён после rollback'а.

**Было:** `RootAllocator` без лимита памяти + `childAllocator` на каждый вызов `buildArrowData()`. Native-память не возвращалась ОС, при длительной работе off-heap росла.

**Исправлено:** Arrow заменён на JSONB в PostgreSQL — `AggregateCandleBuilder.buildPriceLevelsJson()` строит JSON напрямую, сохраняется через `dao.saveAggregate()`. Никаких нативных аллокаций, память под GC.

---

### ~~CRITICAL #10: Одиночные INSERT для filtered_trades~~ ✅ RESOLVED

**Файл:** `service/VolumeFilterProcessor.kt:202` → `VolumeFilterProcessor.kt:203-225, TradeProcessor.kt:151`

**Было:** `dao.insertFilteredTrade(filteredTrade)` — каждая китовая сделка отдельным SQL-запросом. Метод `insertFilteredTradesBatch()` уже был реализован в DAO, но не вызывался.

**Исправлено:**
- Добавлен `filteredTradeBuffer: Collections.synchronizedList<FilteredTrade>` с авто-flush при достижении 100 элементов
- Метод `flushFilteredTrades()`: атомарно вынимает батч, вызывает `dao.insertFilteredTradesBatch()`, при ошибке кладёт обратно
- В `TradeProcessor.shutdown()` добавлен вызов `volumeFilterProcessor.flushFilteredTrades()` для слива остатков

---

### ~~CRITICAL #11: Data Race в `TradeProcessor.InstrumentStats`~~ ✅ RESOLVED

**Файл:** `service/TradeProcessor.kt:37-41, 77-82`

**Было:** `data class InstrumentStats(var totalTrades: Long = 0, ...)` — `totalTrades++` из параллельных корутин — read-modify-write над неатомарным `Long`, потеря счётчика.

**Исправлено:** `var Long`/`var Int` заменены на `val AtomicLong`/`val AtomicInteger`. Инкремент/запись через `.incrementAndGet()` и `.set()`.

---

### ~~CRITICAL #12: Нет Circuit Breaker для PostgreSQL~~ ✅ RESOLVED

**Файл:** `service/BatchProcessor.kt:12-61, 69, 124-142`

**Было:** При ошибке `dao.insertRawTradesBatch()` батч клался обратно в очередь — бесконечный ретрай → рост очереди до OOM.

**Исправлено:** Добавлен класс `CircuitBreaker` с состояниями CLOSED → OPEN → HALF_OPEN:
- 3 последовательных ошибки → OPEN (30 сек таймаут)
- В OPEN батчи дропаются (не ре-queue) с логом
- По таймауту → HALF_OPEN, пробует 1 батч
- Успех → CLOSED, ошибка → OPEN
- `flushBatch()` проверяет `isCallAllowed()` перед вставкой

---

### ~~CRITICAL #13: Graceful shutdown без таймаутов~~ ✅ RESOLVED

**Файл:** `service/ShutdownChain.kt` (новый), `service/TradeCollectorService.kt:89`, `Main.kt:61-67`

**Было:** `service.stop()` → `tradeProcessor.shutdown()` → `aggregateProcessor.flushAll()` — синхронно без таймаута. При большом объёме незакрытых свечей systemd мог убить процесс раньше завершения.

**Исправлено:** Создан `ShutdownChain` — fluent-цепочка шагов `.step(name, timeout) { }`, каждый с deadline и обработкой ошибок без разрыва цепочки. Таймауты:
- `TradeProcessor` — 30 сек
- `ExchangeClients` — 15 сек
- `MonitoringServer` — 5 сек
- `DAO` — 15 сек
- Итого ≤75 сек < 90 сек systemd

---

### ~~CRITICAL #14: MonitoringServer только на localhost~~ ✅ RESOLVED

**Файл:** `service/MonitoringServer.kt:22, 35`, `config/AppConfig.kt:73`, `config/config.dev.json`, `config/config.prod.json`

**Было:** `host = "localhost"` захардкожен — в Docker/K8s healthcheck и Prometheus не достучались.

**Исправлено:**
- `MonitoringConfig` получил поле `host: String = "0.0.0.0"`
- `MonitoringServer` принимает `host` параметром, пробрасывается из конфига через `TradeCollectorService`
- `config.dev.json`: `"host": "localhost"` (локальная разработка)
- `config.prod.json`: `"host": "0.0.0.0"` (контейнеры, внешний доступ)
- Добавлен `ConfigManager.loadFromEnv()` — выбирает конфиг по `APP_ENV` (dev/production)
- `.gitignore` очищен от `config.json` и `config/development.json`

---

### ~~CRITICAL #15: HikariCP без leak-detection и keepalive~~ ✅ RESOLVED

**Файл:** `storage/postgres/TradeDAO.kt:35-37`, `Main.kt:54`

**Было:**
- `leakDetectionThreshold` не настроен — утечка соединений не обнаружится до OOM
- `keepaliveTime` не настроен — idle-соединения могут быть убиты firewall/балансировщиком
- `connectionTestQuery` не настроен — пул может выдать мёртвое соединение перед запросом

**Исправлено:**
- `leakDetectionThreshold = 2000` — логирует если соединение занято >2 сек
- `keepaliveTime = 300_000` — пингует idle-соединения каждые 5 мин
- `connectionTestQuery = "SELECT 1"` — проверяет живость перед выдачей
- `Main.kt` — лог подключения с адресом: `Connecting to PostgreSQL at host:port/db...`

---

### 🟡 MEDIUM #16: t-digest не используется

Библиотека `com.tdunning:t-digest:3.3` добавлена (build.gradle.kts:89), но `VolumeFilterProcessor` до сих пор сортирует весь список для перцентилей — O(n log n) каждый `slideStep`. t-digest дал бы O(log n) и константную память.

---

### 🟡 MEDIUM #17: Неиспользуемые зависимости

- `org.jetbrains.exposed:*` (4 модуля, строки 66-69) — весь доступ к БД через raw JDBC
- `com.github.doyaaaaaken:kotlin-csv-jvm:1.9.2` (строка 86)

**Исправление:** Удалить из `build.gradle.kts`.

---

### 🟡 MEDIUM #18: Export не реализован

`ExportConfig` описан в конфигурации, но код экспорта отсутствует. Либо реализовать, либо убрать.

---

### 🟡 MEDIUM #19: Партиционирование без партиций

**Файл:** `sql/001_init_schema.sql:84`
```sql
) PARTITION BY RANGE (timestamp);
```

Таблица `filtered_trades` объявлена партиционированной, но партиции не созданы. Данные попадают в дефолтную партицию — преимуществ нет.

---

### 🟡 MEDIUM #20: `/health` не проверяет зависимости

**Файл:** `service/MonitoringServer.kt:55-63`

Всегда возвращает `"healthy"`, не проверяя PostgreSQL и WebSocket-соединения. Непригоден для Kubernetes readiness probe.

---

### 🟡 MEDIUM #21: Нет DeadLetterQueue и DiskBuffer

Эти компоненты описаны в планах (`critical-issues.md`), но не реализованы. При сбое БД данные теряются (нет персистентного буфера). «Плохие» сообщения не изолируются.

---

## Что ИЗМЕНИЛОСЬ после rollback'а

По сравнению с предыдущей версией кода (до rollback'а):

| Компонент | Было | Стало |
|-----------|------|-------|
| DeadLetterQueue | Был класс | **Отсутствует** |
| DiskBuffer | Был класс | **Отсутствует** |
| Структура адаптеров | `adapters/` | `exchange/binance/`, `exchange/bybit/` |
| Trade.kt | Только `Double` | Добавлен `fromRaw(BigDecimal)` (но всё равно конвертит в Double) |
| BatchProcessor | `MutableMap` | `ConcurrentHashMap` + `ConcurrentLinkedQueue` (лучше, но гонка осталась) |
| MonitoringServer | 4 эндпоинта | Добавлены `/exchanges`, `/database/stats` |
| Закомментированный код | Нет | Много закомментированных полей в `/status` |

**Итог:** rollback откатил на более раннюю версию — меньше реализованных компонентов, больше заглушек.

---

## Приоритетный план исправлений

### Фаза 1 — Блокирующие (неделя 1)
| # | Задача | Оценка |
|---|--------|--------|
| ~~1.1~~ | ~~Удалить пароль из `production.json`, сменить пароль на сервере~~ ✅ | — |
| ~~1.2~~ | ~~Убрать IP из `AppConfig.kt`~~ ✅ | — |
| ~~1.3~~ | ~~Заменить `Double` на `BigDecimal` в `Trade` и всех расчётах~~ ✅ | — |
| ~~1.4~~ | ~~Исправить data race в `BatchProcessor` (synchronized на flushBatch)~~ ✅ | — |
| ~~1.5~~ | ~~Исправить data race в `VolumeFilterProcessor` (Mutex на окно)~~ ✅ (synchronized per-instrument) | — |
| ~~1.3~~ | ~~Написать минимальный набор тестов~~ → есть 56 тестов, остались пробелы (см. выше) | — |

### Фаза 2 — Критические (неделя 2)
| # | Задача | Оценка |
|---|--------|--------|
| 2.1 | Убрать двойную загрузку конфига, путь `config/production.json` | 15 мин |
| ~~2.2~~ | ~~Исправить resource leak в `ExchangeClient`~~ ✅ | — |
| ~~2.3~~ | ~~Заменить Arrow на JSONB в `AggregateProcessor`~~ ✅ | — |
| ~~2.4~~ | ~~Batch-вставка `filtered_trades`~~ ✅ | — |
| ~~2.5~~ | ~~Atomic-поля в `InstrumentStats`~~ ✅ | — |
| ~~2.6~~ | ~~Circuit Breaker для БД~~ ✅ | — |
| ~~2.7~~ | ~~Таймауты в graceful shutdown~~ ✅ | — |
| ~~2.8~~ | ~~`0.0.0.0` для MonitoringServer~~ ✅ | — |
| ~~2.9~~ | ~~HikariCP: leakDetection + keepalive + validation~~ ✅ | — |

### Фаза 3 — Средние (неделя 3)
| # | Задача | Оценка |
|---|--------|--------|
| 3.1 | Интегрировать t-digest в VolumeFilterProcessor | 4 ч |
| 3.2 | Удалить неиспользуемые зависимости | 15 мин |
| 3.3 | Реализовать DeadLetterQueue | 4 ч |
| 3.4 | Реализовать DiskBuffer | 4 ч |
| 3.5 | Создать партиции для `filtered_trades` | 1 ч |
| 3.6 | `/health` с проверкой БД и WebSocket | 1 ч |

---

## Итог

| Категория | Количество |
|-----------|-----------|
| 🔴 BLOCKER | 0 |
| 🔴 CRITICAL | 0 |
| 🟡 MEDIUM | 7 |
| **Всего** | **7** |

**Оценка трудозатрат:** ~50 человеко-часов (2 недели для одного разработчика).

**Текущий статус после rollback'а:** Проект стал более сырым — отсутствуют DeadLetterQueue и DiskBuffer, больше закомментированного кода. Основные блокеры (пароль, IP, Double, data races) не изменились.
