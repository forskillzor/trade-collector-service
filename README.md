# Trade Collector

Сервис сбора и агрегации трейдов с криптобирж (Binance, Bybit) в реальном времени.
Сохраняет сырые сделки, строит price-level агрегаты (footprint-свечи), вычисляет статистику объёмов и обнаруживает аномально крупные сделки.

**Архитектура**: WebSocket → raw_trades (лёгкий горячий цикл, ~1000 TPS на 1 ядре) → BatchScheduler (раз в минуту строит агрегаты + статистику из БД).

## Быстрый старт (dev)

```bash
# 1. Поднять PostgreSQL
make dev-up

# 2. Собрать и запустить
make dev-run

# 3. Открыть дашборд
open http://localhost:8080
```

Переменные окружения для локальной разработки (скопировать `.env.example` → `.env`):
```bash
DB_HOST=localhost
DB_PORT=5432
DB_NAME=trade_collector
DB_USER=trade_user
DB_PASSWORD=dev_password
```

## Production деплой

### Первый деплой на голый VPS

```bash
# 1. Скопировать setup-vps.sh на VPS и запустить
scp scripts/setup-vps.sh root@vps:/tmp/
ssh root@vps "DB_PASSWORD=strong_password bash /tmp/setup-vps.sh v0.1.0"
```

Скрипт установит Java 21, PostgreSQL, создаст БД и пользователя, установит systemd-сервис.

### Деплой новой версии

**Вариант A — GitHub Actions** (автоматический):
```bash
git tag v0.1.1 && git push origin v0.1.1
# → release.yml собирает и создаёт GitHub Release
# → Actions → Deploy → ввести v0.1.1 → деплой на VPS
```

**Вариант B — Локальный деплой** (Makefile):
```bash
VPS_HOST=95.81.99.28 VPS_USER=deploy VPS_SSH_KEY=~/.ssh/vps_key VERSION=v0.1.1 make deploy
```

### Конфигурация на VPS

Файл `/etc/default/trade-collector` (создаётся автоматически при `setup-vps.sh`):
```bash
DB_HOST=localhost
DB_PORT=5432
DB_NAME=trade_collector
DB_USER=trade_user
DB_PASSWORD=strong_password
```

## Мониторинг

- **Дашборд**: `http://vps:8080/` — TPS, CPU, heap, логи, агрегаты по символам, поминутная гистограмма
- **Health**: `http://vps:8080/health`
- **Метрики**: `http://vps:8080/metrics`
- **Статус**: `http://vps:8080/status`

## Структура проекта

```
trade-collector/
├── src/main/kotlin/
│   ├── Main.kt                          # Точка входа
│   ├── config/                          # Конфигурация
│   ├── exchange/                        # Адаптеры бирж (Binance, Bybit)
│   ├── model/                           # Модели данных
│   ├── service/                         # Бизнес-логика
│   │   ├── TradeProcessor.kt            # Горячий цикл: парсинг + raw_trades
│   │   ├── BatchScheduler.kt            # Пакетная обработка агрегатов
│   │   └── TradeCollectorService.kt     # Оркестратор
│   └── storage/postgres/                # Доступ к БД
├── static/index.html                    # Дашборд (Vue.js)
├── scripts/                             # Деплой-скрипты
├── config/                              # Конфиги (dev/prod)
└── docker-compose.yml                   # PostgreSQL для локальной разработки
```

## База данных

Таблицы создаются автоматически при первом трейде символа по шаблону `{тип}_{symbol}`:
- `raw_trades_btcusdt` — сырые сделки (~10K строк)
- `aggregates_btcusdt` — минутные и 15-минутные свечи с ценовыми уровнями (JSONB)
- `filtered_trades_btcusdt` — аномально крупные сделки
- `volume_windows_btcusdt` — статистика скользящего окна объёмов

Сброс БД на VPS: `sudo -u postgres psql -d trade_collector` → удалить все `raw_trades_%` таблицы вручную или через `scripts/reset-database.sh`.

## Команды Makefile

| Команда | Описание |
|---|---|
| `make dev-up` | Поднять PostgreSQL в Docker |
| `make dev-run` | Собрать и запустить локально |
| `make build` | Собрать fat JAR |
| `make test` | Прогнать тесты |
| `make package VERSION=v0.1.0` | Собрать архив для деплоя |
| `make deploy` | Собрать и задеплоить на VPS |
| `make db-reset` | Сбросить локальную БД |
