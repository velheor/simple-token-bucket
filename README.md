# Simple Token Bucket

Token Bucket Rate Limiter с поддержкой синхронного и асинхронного вызова целевого сервиса и приоритетом в распределении токенов.

Проект демонстрирует, как реализовать **дистрибутивный rate limiter** на базе PostgreSQL: состояние бакета хранится в БД, а параллельность обеспечивается реляционными блокировками. Это позволяет разворачивать несколько экземпляров приложения, и все они будут «видеть» общий запас токенов.

---

## Содержание

- [Архитектура](#архитектура)
- [Технологический стек](#технологический-стек)
- [Требования](#требования)
- [Быстрый старт](#быстрый-старт)
  - [1. Создание базы данных](#1-создание-базы-данных)
  - [2. Запуск приложения](#2-запуск-приложения)
  - [3. Запуск load-теста](#3-запуск-load-теста)
- [API](#api)
  - [POST /api/sync](#post-apisync)
  - [POST /api/async](#post-apiasync)
  - [GET /api/bucket](#get-apibucket)
- [Конфигурация](#конфигурация)
- [Как это работает](#как-это-работает)
  - [Синхронный вызов](#синхронный-вызов)
  - [Асинхронный вызов](#асинхронный-вызов)
  - [Функция `consume_token`](#функция-consume_token)
- [Модель базы данных](#модель-базы-данных)
- [Структура проекта](#структура-проекта)
- [Сборка и тестирование](#сборка-и-тестирование)

---

## Архитектура

```
                        ┌─────────────────────────────────────────────┐
                        │              Spring Boot App                │
                        │                                             │
  ┌───────────┐         │  ┌─────────────────────────────────────┐    │
  │  HTTP     │         │  │        RateLimitController          │    │
  │  Client   │────────►│  │  /sync  /async  /bucket             │    │
  └───────────┘         │  └──────────┬──────────────────┬────────┘    │
                        │             │                  │             │
                        │  ┌──────────▼─────────┐  ┌─────▼──────────┐  │
                        │  │ TargetServiceClient │  │  AsyncWorker   │  │
                        │  │ (sync: poll + wait) │  │  (@Scheduled   │  │
                        │  └──────────┬─────────┘  │   poll/claim)  │  │
                        │             │            └─────┬──────────┘  │
                        │             │                  │             │
                        │  ┌──────────▼──────────────────▼──────────┐  │
                        │  │          TokenBucketService            │  │
                        │  │  tryConsumeSync / tryConsumeAsync      │  │
                        │  └──────────────────┬─────────────────────┘  │
                        └─────────────────────┼────────────────────────┘
                                              │  SELECT consume_token(...)
                                              ▼
                        ┌─────────────────────────────────────────────┐
                        │               PostgreSQL                    │
                        │  rate_limit_bucket  async_tasks  sync_requests
                        │  + функция consume_token()                  │
                        └─────────────────────────────────────────────┘
```

Ключевые компоненты:

| Компонент | Файл | Назначение |
|-----------|------|-----------|
| `RateLimitController` | `controller/RateLimitController.java` | REST-эндпоинты |
| `TargetServiceClient` | `service/TargetServiceClient.java` | Синхронный вызов с ожиданием токена |
| `AsyncWorker` | `service/AsyncWorker.java` | Фоновая очередь асинхронных задач |
| `TokenBucketService` | `service/TokenBucketService.java` | Обёртка над SQL-функцией `consume_token` |
| `RateLimitProperties` | `config/RateLimitProperties.java` | Настройки rate limit |
| `consume_token()` | `db/changelog/002-bucket-function.sql` | Ядро лимитера (PostgreSQL) |

---

## Технологический стек

- **Java 21**
- **Spring Boot 3.3.5**
- **Spring Web / JDBC / Validation**
- **PostgreSQL** — хранение состояния бакета и очереди задач
- **Liquibase** — миграции схемы БД
- **Lombok** — генерация геттеров/сеттеров/конструкторов
- **HikariCP** — пул соединений

---

## Требования

- **JDK 21+**
- **Maven 3.6+**
- **PostgreSQL 14+**

---

## Быстрый старт

### 1. Создание базы данных

```sql
CREATE DATABASE "simple-token-bucket";
CREATE USER admin WITH PASSWORD 'admin';
GRANT ALL PRIVILEGES ON DATABASE "simple-token-bucket" TO admin;
```

По умолчанию приложение подключается к:

```
jdbc:postgresql://localhost:5432/simple-token-bucket
user: admin
password: admin
```

При необходимости измените `src/main/resources/application.yml`.

### 2. Запуск приложения

```bash
mvn spring-boot:run
```

При старте Liquibase автоматически создаёт:
- таблицы `rate_limit_bucket`, `async_tasks`, `sync_requests`
- SQL-функцию `consume_token`

Приложение стартует на `http://localhost:8080`.

> Бакет создаётся лениво: при первом обращении к нему строка `rate_limit_bucket` вставляется автоматически с полным запасом токенов.

### 3. Запуск load-теста

В проекте есть встроенный `LoadTestRunner` (`src/main/java/.../LoadTestRunner.java`), который:

1. Запускает **10 синхронных потоков** по **10 запросов** каждый (`/api/sync`).
2. Отправляет **10 асинхронных запросов** (`/api/async`).
3. Ожидает завершения фоновых задач и печатает итоговый статус бакета.

Запуск:

```bash
mvn spring-boot:run
```

`LoadTestRunner` является `CommandLineRunner`, поэтому срабатывает сразу после старта приложения. Чтобы отключить его, удалите аннотацию `@Component` в `LoadTestRunner.java` или запустите класс отдельно.

Пример лога:

```
[SYNC T1-1] OK in 2 ms — result=sync-response:sync-1
[SYNC T1-2] RATE LIMITED after 2003 ms — rate limit exceeded for sync
[ASYNC 1] ACCEPTED in 4 ms — taskId=1, endpoint=/target/async-1
...
[SYNC T1 SUMMARY] ok=5, limited=5, total=12345ms, avg=1234ms
=== Final bucket status ===
Tokens: 2
Sync granted: 42, denied: 58
Async granted: 10, denied: 3
```

---

## API

Базовый URL: `http://localhost:8080/api`

### POST /api/sync

Синхронный вызов целевого сервиса. Клиент **ожидает** токен до `max-wait-ms`, затем получает результат или `429 Too Many Requests`.

**Запрос:**

```json
{ "payload": "some-data" }
```

Тело может быть опущено (payload по умолчанию `"sync"`).

**Ответ `200 OK`:**

```json
{ "status": "ok", "result": "sync-response:some-data" }
```

**Ответ `429 Too Many Requests`:**

```json
{ "status": "rate_limited", "result": null, "error": "rate limit exceeded for sync" }
```

Пример:

```bash
curl -X POST http://localhost:8080/api/sync \
  -H "Content-Type: application/json" \
  -d '{"payload":"hello"}'
```

### POST /api/async

Отправляет задачу в асинхронную очередь. Возвращает `202 Accepted` с идентификатором задачи. Фоновый воркер подберёт задачу позже и выполнит её, когда станет доступен токен.

**Запрос:**

```json
{ "endpoint": "/target/async", "payload": "some-data" }
```

По умолчанию `endpoint = /target/async`, `payload = "async"`.

**Ответ `202 Accepted`:**

```json
{ "status": "accepted", "taskId": 42, "endpoint": "/target/async" }
```

Пример:

```bash
curl -X POST http://localhost:8080/api/async \
  -H "Content-Type: application/json" \
  -d '{"payload":"bg-work"}'
```

### GET /api/bucket

Возвращает текущее состояние бакета и счётчики выданных/отклонённых токенов.

**Ответ `200 OK`:**

```json
{
  "tokens": 2,
  "syncGranted": 42,
  "syncDenied": 58,
  "asyncGranted": 10,
  "asyncDenied": 3
}
```

Пример:

```bash
curl http://localhost:8080/api/bucket
```

| Поле | Описание |
|------|----------|
| `tokens` | Текущее число токенов в бакете |
| `syncGranted` | Сколько синхронных запросов получили токен |
| `syncDenied` | Сколько синхронных запросов отклонено (таймаут) |
| `asyncGranted` | Сколько асинхронных задач получили токен |
| `asyncDenied` | Сколько раз асинхронным задачам не хватило токенов (задача отложена) |

> Счётчики `syncGranted/syncDenied/asyncGranted/asyncDenied` хранятся в памяти (JVM) и обнуляются при рестарте приложения.

---

## Конфигурация

Все настройки rate limit задаются в `application.yml` под префиксом `rate-limit`:

```yaml
rate-limit:
  bucket-key: target-service      # ключ бакета (строка, идентифицирует лимитируемый ресурс)
  capacity: 3                     # максимальная ёмкость бакета (токенов)
  refill-rate: 3                  # скорость пополнения (токенов в секунду)
  sync:
    max-wait-ms: 2000             # сколько ждать токен в sync-вызове
    poll-interval-ms: 20          # интервал опроса БД при ожидании токена
    min-tokens-after: 0           # мин. токенов, которые должны остаться после снятия (sync)
  async:
    min-tokens-after: 1           # мин. токенов после снятия (async) — резерв для sync
    poll-delay-ms: 50             # период опроса очереди задач
    retry-delay-ms: 200           # задержка повторной попытки (async)
```

| Свойство | По умолчанию | Описание |
|----------|--------------|----------|
| `bucket-key` | `target-service` | Идентификатор ресурса, для которого ведётся ограничение |
| `capacity` | `3` | Максимальный запас токенов |
| `refill-rate` | `3` | Сколько токенов добавляется в секунду |
| `sync.max-wait-ms` | `2000` | Максимальное время ожидания токена в sync-режиме |
| `sync.poll-interval-ms` | `20` | Интервал опроса при ожидании токена |
| `sync.min-tokens-after` | `0` | Минимум остатка, который sync-запрос считает приемлемым |
| `async.min-tokens-after` | `1` | Резерв: async не снимет токен, если после него останется меньше 1 — то есть **sync всегда имеет приоритет** над последним токеном |
| `async.poll-delay-ms` | `50` | Как часто воркер проверяет очередь |
| `async.retry-delay-ms` | `200` | Пауза перед повторной попыткой, если токена нет |

**Приоритет sync над async** реализуется через разные значения `min-tokens-after`: sync-запрос снимает токен, если после снятия останется `>= 0`, а async — только если останется `>= 1`. Таким образом, последний токен всегда отдаётся синхронному клиенту.

---

## Как это работает

### Синхронный вызов

1. Клиент шлёт `POST /api/sync`.
2. `TargetServiceClient.callSync()` вступает в цикл:
   - вызывает `TokenBucketService.tryConsumeSync()` → SQL-функция `consume_token(...)`;
   - если токен получен — выполняет вызов целевого сервиса и возвращает результат;
   - если токена нет — спит `poll-interval-ms` и повторяет.
3. Если за `max-wait-ms` токен не появился — выбрасывается `TooManyRequestsException` → клиент получает **429**.
4. Каждый запрос логируется в таблицу `sync_requests` (payload, статус, время ожидания).

### Асинхронный вызов

1. Клиент шлёт `POST /api/async`.
2. `AsyncWorker.insertTask()` сохраняет задачу в `async_tasks` со статусом `new` и `next_attempt_at = now()`.
3. Клиент сразу получает `202 Accepted` с `taskId`.
4. Фоновый `@Scheduled`-метод `AsyncWorker.poll()` каждые `poll-delay-ms`:
   - атомарно «забирает» задачу через `FOR UPDATE SKIP LOCKED` (статус `processing`);
   - пытается снять токен через `tryConsumeAsync()`;
   - если токен получен — выполняет задачу и помечает её `done`;
   - если токена нет — возвращает задачу в `new` с увеличенной `next_attempt_at` (backoff).
5. При ошибке выполнения — задача возвращается в очередь с **экспоненциальным backoff** (`2^attempts` секунд).

Блокировка `FOR UPDATE SKIP LOCKED` гарантирует, что в масштабе нескольких экземпляров воркера каждая задача будет обработана ровно одним воркером.

### Функция `consume_token`

Ядро лимитера — SQL-функция (см. `db/changelog/002-bucket-function.sql`):

```sql
consume_token(p_key, p_capacity, p_refill_rate, p_min_tokens_after) RETURNS boolean
```

Логика:

1. **Ленивое создание бакета**: если строки `rate_limit_bucket` с `p_key` нет, создаётся полная (tokens = capacity).
2. **Блокировка строки** `FOR UPDATE` — защищает от гонок между параллельными вызовами.
3. **Пополнение (refill)**: `elapsed = now() - last_refill`; новые токены = `elapsed * refill_rate`, ограничено `capacity`.
4. **Снятие**: если `tokens - 1 >= p_min_tokens_after` — токен снимается, `last_refill` обновляется, возвращается `true`. Иначе `false`.

Запись состояния в БД делает лимитер **дистрибутивным**: все инстансы приложения делят один и тот же бакет.

---

## Модель базы данных

Миграции выполняются Liquibase (`db/changelog/db.changelog-master.yaml`):

### `rate_limit_bucket`

| Колонка | Тип | Описание |
|---------|-----|----------|
| `bucket_key` | `text PK` | Идентификатор лимитируемого ресурса |
| `tokens` | `int` | Текущее число токенов |
| `capacity` | `int` | Максимальная ёмкость |
| `refill_rate` | `int` | Токенов в секунду |
| `last_refill` | `timestamptz` | Время последнего пополнения |

### `async_tasks`

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | `bigserial PK` | Идентификатор задачи |
| `endpoint` | `text` | Целевой endpoint |
| `payload` | `text` | Данные задачи |
| `status` | `text` | `new` / `processing` / `done` |
| `attempts` | `int` | Число попыток (для backoff) |
| `locked_by` | `text` | ID воркера, обработавшего задачу |
| `locked_at` | `timestamptz` | Момент захвата |
| `next_attempt_at` | `timestamptz` | Когда задачу можно обработать снова |
| `created_at` | `timestamptz` | Время создания |

Индекс: `idx_async_tasks_status_next (status, next_attempt_at)`.

### `sync_requests`

| Колонка | Тип | Описание |
|---------|-----|----------|
| `id` | `bigserial PK` | Идентификатор записи |
| `payload` | `text` | Payload запроса |
| `status` | `text` | `ok` / `rate_limited` |
| `wait_ms` | `bigint` | Сколько миллисекунд клиент ждал токен |
| `created_at` | `timestamptz` | Время записи |

Индекс: `idx_sync_requests_created (created_at)`.

---

## Структура проекта

```
simple-token-bucket
├── pom.xml
├── src
│   ├── main
│   │   ├── java/org/example/tokenbucket
│   │   │   ├── SimpleTokenBucketApplication.java   # точка входа, @EnableScheduling
│   │   │   ├── LoadTestRunner.java                 # встроенный load-тест (CommandLineRunner)
│   │   │   ├── config
│   │   │   │   └── RateLimitProperties.java        # настройки rate-limit.*
│   │   │   ├── controller
│   │   │   │   └── RateLimitController.java        # REST API (/sync /async /bucket)
│   │   │   ├── entity
│   │   │   │   └── AsyncTask.java                  # сущность async_tasks
│   │   │   ├── exception
│   │   │   │   └── TooManyRequestsException.java   # 429
│   │   │   └── service
│   │   │       ├── TokenBucketService.java         # обёртка над consume_token + счётчики
│   │   │       ├── TargetServiceClient.java        # sync: ожидание токена + вызов
│   │   │       └── AsyncWorker.java                # фоновый воркер очереди
│   │   └── resources
│   │       ├── application.yml
│   │       └── db/changelog
│   │           ├── db.changelog-master.yaml
│   │           ├── 001-init.sql                    # таблицы + индекс
│   │           ├── 002-bucket-function.sql         # функция consume_token
│   │           └── 003-sync-requests.sql           # таблица sync_requests
│   └── test
```

---

## Сборка и тестирование

```bash
# компиляция
mvn compile

# тесты
mvn test

# сборка JAR
mvn package

# запуск собранного JAR
java -jar target/simple-token-bucket-1.0-SNAPSHOT.jar
```

После запуска проверить работоспособность:

```bash
curl http://localhost:8080/api/bucket
curl -X POST http://localhost:8080/api/sync -d '{"payload":"test"}' -H "Content-Type: application/json"
curl -X POST http://localhost:8080/api/async -d '{"payload":"bg"}' -H "Content-Type: application/json"
```
