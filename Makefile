.PHONY: help dev-up dev-down dev-run dev-restart test lint build clean db-init db-reset

DOCKER := $(shell command -v docker 2>/dev/null)
COMPOSE := $(shell docker compose version >/dev/null 2>&1 && echo "docker compose" || echo "docker-compose")

# ===== ЛОКАЛЬНАЯ РАЗРАБОТКА =====

dev-up:          ## Запустить PostgreSQL локально
	$(COMPOSE) up -d
	@echo "✅ PostgreSQL запущен на localhost:5432"
	@echo "   БД: trade_collector | Пользователь: trade_user | Пароль: dev_password"

dev-down:        ## Остановить PostgreSQL
	$(COMPOSE) down

dev-run:         ## Собрать и запустить сервис локально
	@test -f config.json || (cp config/config.example.json config.json && echo "📄 config.json создан из config.example.json")
	./gradlew shadowJar --no-daemon
	DB_HOST=localhost DB_PORT=5432 DB_NAME=trade_collector DB_USER=trade_user DB_PASSWORD=dev_password java -jar build/libs/trade-collector.jar

dev-restart:     ## Перезапустить сервис (без пересборки)
	DB_HOST=localhost DB_PORT=5432 DB_NAME=trade_collector DB_USER=trade_user DB_PASSWORD=dev_password java -jar build/libs/trade-collector.jar

test:            ## Прогнать тесты
	./gradlew test --no-daemon

lint:            ## Проверить код (нужен ktlint в build.gradle.kts)
	./gradlew ktlintCheck --no-daemon

build:           ## Собрать shadowJar
	./gradlew shadowJar --no-daemon

clean:           ## Очистить сборку
	./gradlew clean --no-daemon

# ===== БАЗА ДАННЫХ (локально) =====

db-init:         ## Применить схему БД локально (без удаления данных)
	PGPASSWORD=dev_password psql -h localhost -U trade_user -d trade_collector -f sql/001_init_schema.sql
	@echo "✅ Схема применена"

db-reset:        ## Полностью сбросить локальную БД
	$(COMPOSE) down -v
	$(COMPOSE) up -d
	@echo "✅ БД пересоздана, схема применена автоматически"

db-psql:         ## Подключиться к локальной БД
	PGPASSWORD=dev_password psql -h localhost -U trade_user -d trade_collector

# ===== ПРОЧЕЕ =====

help:            ## Показать эту справку
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | sort | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-18s\033[0m %s\n", $$1, $$2}'
