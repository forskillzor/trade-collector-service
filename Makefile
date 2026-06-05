.PHONY: help dev-up dev-down dev-run dev-restart test build clean db-init db-reset db-psql

DOCKER := $(shell command -v docker 2>/dev/null)
COMPOSE := $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose")
-include .env
export

DB_HOST ?= localhost
DB_PORT ?= 5432
DB_NAME ?= trade_collector
DB_USER ?= trade_user
DB_PASSWORD ?= dev_password

# ===== ЛОКАЛЬНАЯ РАЗРАБОТКА =====

dev-up:          ## Запустить PostgreSQL локально
	$(COMPOSE) up -d
	@echo "✅ PostgreSQL запущен на $(DB_HOST):$(DB_PORT)"
	@echo "   БД: $(DB_NAME) | Пользователь: $(DB_USER)"

dev-down:        ## Остановить PostgreSQL
	$(COMPOSE) down

dev-run: build   ## Собрать и запустить сервис локально
	DB_HOST=$(DB_HOST) DB_PORT=$(DB_PORT) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) APP_ENV=dev java -jar build/libs/trade-collector.jar

dev-restart:     ## Перезапустить сервис (без пересборки)
	DB_HOST=$(DB_HOST) DB_PORT=$(DB_PORT) DB_NAME=$(DB_NAME) DB_USER=$(DB_USER) DB_PASSWORD=$(DB_PASSWORD) APP_ENV=dev java -jar build/libs/trade-collector.jar

test:            ## Прогнать тесты
	./gradlew test --no-daemon

build:           ## Собрать shadowJar
	./gradlew shadowJar --no-daemon

clean:           ## Очистить сборку
	./gradlew clean --no-daemon

# ===== БАЗА ДАННЫХ (локально) =====

db-init:         ## Применить схему БД локально (uuid-ossp extension)
	PGPASSWORD=$(DB_PASSWORD) psql -h $(DB_HOST) -U $(DB_USER) -d $(DB_NAME) -f sql/001_init_schema.sql
	@echo "✅ Extension uuid-ossp создан (таблицы создадутся при запуске коллектора)"

db-reset:        ## Полностью сбросить локальную БД
	$(COMPOSE) down -v
	$(COMPOSE) up -d
	@echo "⏳ Ожидание PostgreSQL..."
	@until PGPASSWORD=$(DB_PASSWORD) psql -h $(DB_HOST) -U $(DB_USER) -d $(DB_NAME) -c "SELECT 1" >/dev/null 2>&1; do sleep 1; done
	@echo "✅ БД пересоздана (таблицы создадутся автоматически при первом трейде)"

db-psql:         ## Подключиться к локальной БД
	PGPASSWORD=$(DB_PASSWORD) psql -h $(DB_HOST) -U $(DB_USER) -d $(DB_NAME)

# ===== ПРОЧЕЕ =====

help:            ## Показать эту справку
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}'
