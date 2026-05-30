# Development Workflow — TradeCollectorService

**Цель:** Удобный итеративный процесс: локальная разработка → CI-сборка → деплой на VPS по git tag. С сохранением данных при пересоздании БД.

---

## 1. Обзор текущего CI/CD и его проблемы

### Что есть сейчас

| Триггер | Workflow | Что делает |
|---------|----------|------------|
| `git push tag v*` | `build.yml` | `./gradlew shadowJar` → `prepare-package.sh` → GitHub Release |
| `workflow_dispatch` | `deploy.yml` | Качает release asset → SCP на VPS → запускает `deploy-remote.sh` |
| `workflow_dispatch` | `TestSSH.yml` | Проверяет SSH-доступ |

### Проблемы текущего CI/CD

| # | Проблема | Где |
|---|----------|-----|
| 1 | `prepare-package.sh:30` копирует `config.json`, а реальный конфиг — `config/production.json` | build |
| 2 | `deploy.yml:92` — `DB_PASSWORD` в SSH heredoc, виден в логах GitHub Actions | deploy |
| 3 | `deploy-remote.sh` требует `config.json` в пакете, а не `production.json` | deploy |
| 4 | `init-database.sh` вызывает `DROP TABLE IF EXISTS raw_trades CASCADE` из `001_init_schema.sql` — удаляет все данные | deploy |
| 5 | ~~run.sh пытается скачать PostgreSQL драйвер отдельно — не нужно, он уже в shadowJar~~ ✅ Исправлено | runtime |
| 6 | ~~`run.sh:72` — `Xmx512m` мало для Arrow (off-heap не лимитируется)~~ ✅ Arrow удалён, Xmx512m теперь достаточно | runtime |
| 7 | ~~Dockerfile использует GraalVM native-image — несовместим с Arrow/JNI~~ ✅ Удалён (docker не нужен) | docker |
| 8 | Нет локального dev-окружения (docker-compose для PostgreSQL) | dev |
| 9 | Нет шага тестов в CI (`./gradlew test` не вызывается) | CI |
| 10 | Деплой не атомарный — при ошибке сервис остаётся в сломанном состоянии | deploy |
| 11 | Нет бэкапа данных перед пересозданием БД | deploy |

---

## 2. Целевой Workflow

### 2.1. Структура конфигов (предлагаемая)

```
├── config/
│   ├── config.json              # Шаблон для локальной разработки (в .gitignore)
│   ├── config.example.json      # Пример конфига в репозитории (без паролей)
│   └── production.json          # Продакшен-конфиг (без пароля! только структура)
├── docker-compose.yml           # Локальный PostgreSQL для разработки
├── Makefile                     # Удобные команды для dev/ci/deploy
├── .env.example                 # Пример переменных окружения
└── .gitignore                   # Исключаем config/config.json, .env
```

### 2.2. Локальная разработка (цикл «изменил → проверил → запустил»)

```bash
# Первый запуск
make dev-up          # docker-compose up -d (PostgreSQL)
make dev-db-init     # Применить схему БД
make dev-run         # ./gradlew run (или shadowJar + java -jar)

# Итерация
make test            # ./gradlew test
make lint            # ./gradlew ktlintCheck (добавить ktlint в build.gradle.kts)
make dev-run         # Перезапустить

# Очистка
make dev-down        # docker-compose down
make dev-clean       # Удалить всё + том с данными БД
```

**Ключевой момент:** Весь цикл — без CI. `make dev-run` запускает сервис локально, подключаясь к PostgreSQL в docker-compose. Конфиг `config/config.json` — локальный, не коммитится.

### 2.3. CI: Push в ветку

Триггер: `push` в любую ветку (кроме тегов)

```yaml
# .github/workflows/ci.yml (новый, заменяет build.yml)
on:
  push:
    branches: ['**']
    tags-ignore: ['v*']
```

Что делает:
1. `./gradlew test` — прогоняет тесты
2. `./gradlew shadowJar` — собирает jar (без упаковки в архив)
3. (опционально) `./gradlew ktlintCheck`

**JAR артефакт сохраняется в GitHub Actions cache на 1 день** — для быстрых ручных деплоев.

### 2.4. CI/CD: Git Tag → Release + Deploy

Триггер: `git tag v*` (например `v1.0.0`)

```yaml
# .github/workflows/release.yml (объединяет build.yml + deploy.yml)
on:
  push:
    tags: ['v*']
```

Шаги:
1. `./gradlew test` — тесты
2. `./gradlew shadowJar` — сборка
3. `prepare-package.sh` — упаковка в `trade-collector-v1.0.0.tar.gz`
4. GitHub Release с артефактом
5. Деплой на VPS:
   - Скачать архив на VPS
   - **Сделать бэкап БД** (`pg_dump`)
   - Остановить сервис
   - Распаковать архив в `/opt/trade-collector/releases/v1.0.0/`
   - Атомарно переключить symlink `current → releases/v1.0.0`
   - Применить миграции БД (если есть новые)
   - Запустить сервис
   - Проверить `/health`
   - При ошибке — откатить на предыдущий релиз

**Безопасность:** `DB_PASSWORD` передаётся через `EnvironmentFile` в systemd, а не через CI. CI не должен знать пароль БД. Вместо этого `deploy-remote.sh` на сервере читает `/etc/default/trade-collector`.

### 2.5. Ручной деплой (workflow_dispatch)

Триггер: ручной запуск из GitHub Actions UI

```yaml
on:
  workflow_dispatch:
    inputs:
      tag_version:
        description: 'Версия для деплоя (v1.0.0)'
        required: true
      skip_backup:
        description: 'Пропустить бэкап БД'
        type: boolean
        default: false
```

Позволяет задеплоить конкретную версию вручную (например, откат).

---

## 3. Бэкап и пересоздание БД

### Проблема

`sql/001_init_schema.sql:3`:
```sql
DROP TABLE IF EXISTS raw_trades CASCADE;
```

При каждом запуске `init-database.sh` удаляет ВСЕ накопленные данные. Нужно:
1. Сохранить данные перед дропом
2. Разделить «создание таблиц» и «очистку данных»

### Решение: разделить SQL-скрипты

```
sql/
├── 001_init_schema.sql          # Только CREATE TABLE (без DROP)
├── 002_seed_data.sql            # Начальные данные (если нужны)
└── migrations/                  # Будущие миграции
    └── 003_add_column_xxx.sql
```

А очистку вынести в отдельный скрипт `scripts/reset-database.sh`, который:
1. Делает `pg_dump` в `/opt/trade-collector/backups/`
2. Только потом `DROP ... CASCADE`
3. Пересоздаёт схему

### Скрипт бэкапа (`scripts/backup-db.sh`)

```bash
#!/bin/bash
# Делает дамп всех таблиц в /opt/trade-collector/backups/
BACKUP_DIR="/opt/trade-collector/backups"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
BACKUP_FILE="$BACKUP_DIR/backup-$TIMESTAMP.sql.gz"

pg_dump -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
    --no-owner --no-acl | gzip > "$BACKUP_FILE"

echo "✅ Бэкап сохранен: $BACKUP_FILE ($(du -h "$BACKUP_FILE" | cut -f1))"

# Оставляем только последние 5 бэкапов
ls -t "$BACKUP_DIR"/backup-*.sql.gz | tail -n +6 | xargs rm -f
```

### Скрипт сброса БД (`scripts/reset-database.sh`)

```bash
#!/bin/bash
# 1. Бэкап
./backup-db.sh
# 2. Дроп и пересоздание
PGPASSWORD="$DB_PASSWORD" psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" << SQL
DROP TABLE IF EXISTS raw_trades, filtered_trades, aggregates, volume_windows CASCADE;
SQL
# 3. Применить схему заново
./init-database.sh
```

### Команда в Makefile

```makefile
db-backup:   ## Сделать бэкап БД на VPS
	ssh $(VPS_USER)@$(VPS_HOST) 'cd /opt/trade-collector && ./backup-db.sh'

db-reset:    ## Сбросить БД с сохранением бэкапа
	ssh $(VPS_USER)@$(VPS_HOST) 'cd /opt/trade-collector && ./reset-database.sh'
```

---

## 4. Атомарный деплой (symlink-подход)

Вместо копирования файлов в `/opt/trade-collector/` напрямую:

```
/opt/trade-collector/
├── current -> releases/v1.0.0     # symlink на активную версию
├── releases/
│   ├── v0.9.0/
│   │   ├── trade-collector.jar
│   │   ├── config.json
│   │   ├── run.sh
│   │   └── ...
│   └── v1.0.0/
│       └── ...
├── backups/
│   └── backup-20260529-120000.sql.gz
├── data/
└── logs/
```

**Алгоритм `deploy-remote.sh` (новая версия):**

1. Проверить архив
2. **Сделать бэкап БД** (`backup-db.sh`)
3. Распаковать в `releases/$VERSION/`
4. Остановить сервис (`systemctl stop`)
5. Сделать `ln -sfn releases/$VERSION current`
6. Применить миграции (`init-database.sh`)
7. Запустить сервис (`systemctl start`)
8. Подождать 5 секунд
9. Проверить `curl localhost:8080/health`
10. Если ошибка — `ln -sfn releases/$PREV_VERSION current && systemctl start` (откат)

---

## 5. Makefile (все команды в одном месте)

```makefile
.PHONY: help dev-up dev-down dev-run test lint build clean deploy

# ===== ЛОКАЛЬНАЯ РАЗРАБОТКА =====

dev-up:          ## Запустить PostgreSQL локально
	docker-compose up -d

dev-down:        ## Остановить PostgreSQL
	docker-compose down

dev-run:         ## Запустить сервис локально
	./gradlew shadowJar && \
	java -jar build/libs/trade-collector.jar

test:            ## Прогнать тесты
	./gradlew test

lint:            ## Проверить код
	./gradlew ktlintCheck

build:           ## Собрать shadowJar
	./gradlew shadowJar

clean:           ## Очистить сборку
	./gradlew clean

# ===== БАЗА ДАННЫХ =====

db-init:         ## Инициализировать БД (локально)
	PGPASSWORD=dev_password psql -h localhost -U trade_user -d trade_collector -f sql/001_init_schema.sql

db-backup-local: ## Бэкап локальной БД
	pg_dump -h localhost -U trade_user trade_collector | gzip > backups/local-$(shell date +%Y%m%d-%H%M%S).sql.gz

db-backup-vps:   ## Бэкап БД на VPS
	ssh $(VPS_USER)@$(VPS_HOST) 'cd /opt/trade-collector && bash backup-db.sh'

db-reset-vps:    ## Сброс БД на VPS с бэкапом
	ssh $(VPS_USER)@$(VPS_HOST) 'cd /opt/trade-collector && bash reset-database.sh'

# ===== ДЕПЛОЙ =====

deploy:          ## Собрать и задеплоить на VPS (нужен VPS_HOST в .env)
	@test -n "$(VERSION)" || (echo "Укажи VERSION=v1.0.0" && exit 1)
	./gradlew shadowJar
	bash scripts/prepare-package.sh $(VERSION)
	scp trade-collector-$(VERSION).tar.gz $(VPS_USER)@$(VPS_HOST):/tmp/
	ssh $(VPS_USER)@$(VPS_HOST) 'cd /opt/trade-collector && bash deploy-remote.sh $(VERSION)'

# ===== ПРОЧЕЕ =====

help:            ## Показать эту справку
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'
```

---

## 6. docker-compose.yml для локальной разработки

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: trade_collector
      POSTGRES_USER: trade_user
      POSTGRES_PASSWORD: dev_password
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./sql/001_init_schema.sql:/docker-entrypoint-initdb.d/001_init_schema.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U trade_user -d trade_collector"]
      interval: 5s
      timeout: 3s
      retries: 5

volumes:
  pgdata:
```

---

## 7. Исправления CI/CD (конкретные изменения)

### 7.1. `.github/workflows/build.yml` → заменить на `ci.yml` + `release.yml`

**`ci.yml`** — запускается на каждый push (кроме тегов):
```yaml
name: CI
on:
  push:
    branches: ['**']
    tags-ignore: ['v*']
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_DB: trade_collector_test
          POSTGRES_USER: trade_user
          POSTGRES_PASSWORD: test_password
        ports: ['5432:5432']
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - name: Test
        run: ./gradlew test --no-daemon
        env:
          DB_HOST: localhost
          DB_PORT: 5432
          DB_USER: trade_user
          DB_PASSWORD: test_password
          DB_NAME: trade_collector_test
```

**`release.yml`** — запускается на тег `v*`:
```yaml
name: Release & Deploy
on:
  push:
    tags: ['v*']

jobs:
  build-and-release:
    runs-on: ubuntu-latest
    permissions:
      contents: write
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '21'
      - name: Build
        run: ./gradlew shadowJar --no-daemon
      - name: Package
        run: bash scripts/prepare-package.sh "${{ github.ref_name }}"
      - name: Release
        uses: softprops/action-gh-release@v1
        with:
          files: trade-collector-*.tar.gz
          generate_release_notes: true

  deploy:
    needs: build-and-release
    runs-on: ubuntu-latest
    steps:
      - name: Setup SSH
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.VPS_SSH_KEY }}" > ~/.ssh/vps_key
          chmod 600 ~/.ssh/vps_key
          ssh-keyscan -H "${{ secrets.VPS_HOST }}" >> ~/.ssh/known_hosts
      - name: Download and deploy
        run: |
          TAG="${{ github.ref_name }}"
          # Качаем архив с GitHub Release
          curl -sL -H "Authorization: Bearer ${{ secrets.GITHUB_TOKEN }}" \
            -o /tmp/archive.tar.gz \
            "https://github.com/${{ github.repository }}/releases/download/$TAG/trade-collector-$TAG.tar.gz"
          # Копируем на VPS
          scp -i ~/.ssh/vps_key /tmp/archive.tar.gz \
            "${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}:/tmp/trade-collector-$TAG.tar.gz"
          # Запускаем деплой (пароль БД уже на сервере, CI его не знает)
          ssh -i ~/.ssh/vps_key "${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}" \
            "cd /opt/trade-collector && bash deploy-remote.sh $TAG"
      - name: Verify
        run: |
          ssh -i ~/.ssh/vps_key "${{ secrets.VPS_USER }}@${{ secrets.VPS_HOST }}" \
            "curl -sf http://localhost:8080/health && echo '✅ Health OK'"
```

**Ключевое отличие:** CI больше не передаёт `DB_PASSWORD`. Пароль живёт только на VPS в `/etc/default/trade-collector` (systemd `EnvironmentFile`).

### 7.2. `prepare-package.sh` — исправить путь к конфигу

```bash
# Было:
if [ -f "config.json" ]; then
    cp config.json deploy-package/
# Стало:
if [ -f "config/production.json" ]; then
    cp config/production.json deploy-package/config.json
```

### 7.3. `sql/001_init_schema.sql` — убрать DROP TABLE

Убрать строку 3: `DROP TABLE IF EXISTS raw_trades CASCADE;`

И строки 5, 22, 58, 94: заменить `CREATE TABLE IF NOT EXISTS` → просто `CREATE TABLE` (чистая установка) или оставить `IF NOT EXISTS` для идемпотентности.

### 7.4. Новый `deploy-remote.sh` (атомарный с symlink)

```bash
#!/bin/bash
set -e
VERSION="${1:-unknown}"
APP_DIR="/opt/trade-collector"
RELEASE_DIR="$APP_DIR/releases/$VERSION"

echo "📦 Deploying version: $VERSION"

# 1. Проверить архив
ARCHIVE="/tmp/trade-collector-$VERSION.tar.gz"
test -f "$ARCHIVE" || { echo "❌ Архив не найден: $ARCHIVE"; exit 1; }

# 2. Бэкап БД
echo "💾 Создаю бэкап..."
bash "$APP_DIR/backup-db.sh" || echo "⚠️ Бэкап не удался, продолжаю..."

# 3. Распаковать в releases/$VERSION
mkdir -p "$RELEASE_DIR"
tar -xzf "$ARCHIVE" -C "$RELEASE_DIR"

# 4. Запомнить предыдущую версию
PREV_VERSION=$(readlink "$APP_DIR/current" | xargs basename 2>/dev/null || echo "")

# 5. Остановить сервис
echo "🛑 Останавливаю сервис..."
systemctl stop trade-collector.service 2>/dev/null || true
sleep 2

# 6. Переключить symlink
ln -sfn "$RELEASE_DIR" "$APP_DIR/current"

# 7. Применить миграции БД (без дропа!)
echo "🗄️ Применяю миграции..."
cd "$APP_DIR/current"
export $(grep -v '^#' /etc/default/trade-collector | xargs)
bash init-database.sh

# 8. Запустить сервис
echo "🚀 Запускаю сервис..."
systemctl start trade-collector.service
sleep 5

# 9. Проверить здоровье
echo "🏥 Проверяю здоровье..."
if curl -sf http://localhost:8080/health > /dev/null 2>&1; then
    echo "✅ Деплой $VERSION успешен!"
    # Удалить старые версии (оставить 3 последних)
    ls -dt "$APP_DIR"/releases/*/ | tail -n +4 | xargs rm -rf 2>/dev/null || true
else
    echo "❌ Health check провален! Откатываю на $PREV_VERSION..."
    ln -sfn "$APP_DIR/releases/$PREV_VERSION" "$APP_DIR/current"
    systemctl start trade-collector.service
    exit 1
fi
```

---

## 8. Типичный цикл разработки (по шагам)

### Исправление бага

```bash
# 1. Создать ветку
git checkout -b fix/batch-processor-race

# 2. Запустить локальное окружение
make dev-up          # PostgreSQL в docker
make dev-run         # Сервис

# 3. Внести изменения в коде
# ...

# 4. Написать/обновить тесты
# ...

# 5. Проверить
make test            # unit-тесты
make lint            # линтер

# 6. Закоммитить и запушить
git add -A && git commit -m "fix: race condition in BatchProcessor.flushBatch"
git push origin fix/batch-processor-race

# 7. CI прогонит тесты автоматически (ci.yml)

# 8. После ревью — мёрж в master

# 9. Создать тег для деплоя
git tag v1.0.1
git push origin v1.0.1

# 10. CI соберёт, создаст Release и задеплоит на VPS (release.yml)
# Проверить: curl http://95.81.99.28:8080/health
```

### Сброс БД на VPS с сохранением данных

```bash
# Перед деплоем, если нужен дроп таблиц
make db-reset-vps

# Или вручную:
ssh deploy@95.81.99.28
cd /opt/trade-collector
bash reset-database.sh    # бэкап + дроп + пересоздание
```

### Откат на предыдущую версию

```bash
ssh deploy@95.81.99.28
cd /opt/trade-collector
ls releases/               # посмотреть доступные версии
ln -sfn releases/v0.9.0 current
systemctl restart trade-collector
```

---

## 9. План внедрения

### Этап 1: Локальная разработка (30 мин)
- [ ] Создать `docker-compose.yml`
- [ ] Создать `config/config.example.json` (без паролей)
- [ ] Добавить `config/config.json` в `.gitignore`
- [ ] Создать `Makefile`
- [ ] Проверить: `make dev-up && make dev-run`

### Этап 2: Исправить SQL и скрипты (30 мин)
- [ ] Убрать `DROP TABLE` из `001_init_schema.sql`
- [ ] Создать `scripts/backup-db.sh`
- [ ] Создать `scripts/reset-database.sh`
- [ ] Исправить `run.sh` (убрать скачивание драйвера, увеличить Xmx)

### Этап 3: Атомарный деплой (1 час)
- [ ] Переписать `deploy-remote.sh` (symlink-подход)
- [ ] Исправить `prepare-package.sh` (путь к конфигу)
- [ ] Создать структуру директорий на VPS (`releases/`, `backups/`)

### Этап 4: CI/CD (1 час)
- [ ] Создать `.github/workflows/ci.yml` (push → test)
- [ ] Переименовать `build.yml` → `release.yml`, добавить deploy job
- [ ] Убрать `DB_PASSWORD` из CI (только на сервере)
- [ ] Удалить `deploy.yml` (объединён с release.yml)

### Этап 5: Валидация (30 мин)
- [ ] `make test` локально
- [ ] Push в ветку → CI зелёный
- [ ] `git tag v1.0.0 && git push --tags` → деплой на VPS
- [ ] `curl http://95.81.99.28:8080/health` → `{"status":"healthy"}`
