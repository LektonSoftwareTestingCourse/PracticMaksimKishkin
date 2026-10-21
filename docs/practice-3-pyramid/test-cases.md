# Тест-кейсы и анализ пирамиды

---

## Часть (б): новые тест-кейсы

Структура: `ID | Связанное требование | Уровень пирамиды | Вид | Предусловие | Шаги | Ожидаемый результат | Источник`.
Покрыты уровни `unit` / `api` / `integration` / `e2e`, а также **sync** (валидация, маршрутизация по BIN, лимиты/баланс, CRUD, резервирование) и **async** (доставка в Logger, publisher-confirm + reversal, outbox → Notification, eventual consistency).

| ID            | Связанное требование                 | Уровень пирамиды | Вид        | Предусловие                                                       | Шаги                                                                                   | Ожидаемый результат                                                                                                   | Источник                            |
| ------------- | ------------------------------------ | ---------------- | ---------- | ----------------------------------------------------------------- | -------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| **TC-NEW-01** | ТЗ 04, п. 2 (статус карты)           | unit             | Негативный | Мок `CardManagementClient` возвращает карту со `status=BLOCKED`   | Вызвать `AuthServiceImpl.authorize(request, now)`                                      | Возвращён `AuthorizationResponse` DECLINED, `declineReason=CARD_BLOCKED`, `responseCode=05`; `reserve()` не вызывался | Класс эквивалентности (статус)      |
| **TC-NEW-02** | ТЗ 04, п. 4 (лимиты)                 | unit             | Негативный | Мок `LimitUsageRepository.upsertLimitUsage` возвращает `0`        | Вызвать `authorize()` с amount в пределах баланса                                      | DECLINED, `declineReason=EXCEEDS_AMOUNT_LIMIT`, `responseCode=61`                                                     | Граничное значение (лимит)          |
| **TC-NEW-03** | ТЗ 04, п. 2 (срок)                   | unit             | Негативный | Мок карты: `expiryDate` в прошлом месяце, `status=ACTIVE`         | Вызвать `authorize()`                                                                  | DECLINED, `declineReason=CARD_EXPIRED`, `responseCode=54`                                                             | Граничное значение (срок)           |
| **TC-NEW-04** | ТЗ 04, п. 2 (баланс)                 | unit             | Негативный | Мок карты: `availableBalance=20000`, amount=20001                 | Вызвать `authorize()`                                                                  | DECLINED, `declineReason=INSUFFICIENT_FUNDS`, `responseCode=51`                                                       | Граничное значение (баланс)         |
| **TC-NEW-05** | ТЗ 05, п. 2 (Luhn)                   | unit             | Негативный | `LuhnValidator`                                                   | Проверить PAN `4000001234560002` (невалиден по Луну)                                   | `false` — контрольная сумма не сходится                                                                               | Класс эквивалентности (PAN)         |
| **TC-NEW-06** | ТЗ 05, п. 2 (Luhn)                   | unit             | Позитивный | `LuhnValidator`                                                   | Проверить PAN `4000001234560001`                                                       | `true` — контрольная сумма сходится                                                                                   | Класс эквивалентности (PAN)         |
| **TC-NEW-07** | ТЗ 05, п. 5 (reserve)                | unit             | Негативный | Мок `ReservationRepository.isUnique` → `false`                    | Вызвать `CardServiceImpl.reserve(pan, amount, rrn)`                                    | Брошено `ReservationAlreadyExistsException`                                                                           | Контракт (уникальность rrn+pan)     |
| **TC-NEW-08** | ТЗ 02 (маршрутизация по BIN)         | api              | Негативный | Switch запущен, BIN `999999` отсутствует в таблице                | `POST /api/internal/route` с PAN `999999...`                                           | Ответ DECLINED, `status=DECLINED` (unknown BIN), HTTP 200                                                             | Класс эквивалентности (BIN)         |
| **TC-NEW-09** | ТЗ 04 (контракт authorize)           | api              | Позитивный | Карта ACTIVE, баланс 20000, лимиты достаточны                     | `POST /api/internal/authorize`, amount=1000                                            | HTTP 200, `status=APPROVED`, `responseCode=00`, RRN 12 цифр, authCode 6 символов                                      | Контракт (запрос → ответ)           |
| **TC-NEW-10** | ТЗ 05 (контракт CRUD)                | api              | Негативный | Карта отсутствует в БД                                            | `GET /api/cards/4000001234567890`                                                      | HTTP 404 Not Found                                                                                                    | Контракт (запрос → ответ)           |
| **TC-NEW-11** | ТЗ 05, п. 3 (generate)               | api              | Позитивный | БД доступна                                                       | `POST /api/cards/generate` `{count:100, bins:["400000"]}`                              | HTTP 201, 100 карт, распределение статусов ≈ 95/3/2                                                                   | Сценарий (генерация)                |
| **TC-NEW-12** | ТЗ 02 (async доставка в Logger)      | integration      | Позитивный | RabbitMQ доступен, очередь `transaction-log` пуста                | Опубликовать `TransactionRequest` в `smp.transactions` (routing key `transaction.log`) | Сообщение потреблено `TransactionLogListener`, запись появилась в таблице `transactions` (Awaitility)                 | Состояние БД (eventual consistency) |
| **TC-NEW-13** | ТЗ 02 (publisher-confirm + reversal) | integration      | Негативный | RabbitMQ недоступен (остановлен)                                  | Выполнить `RouteService.route()` для APPROVED-транзакции                               | `tryLog()` → `false`, вызван rollback (reversal mti=0400), ответ `systemError` `responseCode=96`                      | Сценарий (сбой публикации)          |
| **TC-NEW-14** | ТЗ 05 (outbox → Notification)        | integration      | Позитивный | RabbitMQ доступен, `outbox_events` содержит PENDING               | Запустить `OutboxEventProcessorImpl.processSingleEvent()`                              | Событие опубликовано в `smp.card-events`, статус → `PROCESSED`, `CardEventListener` сохранил `card_notifications`     | Состояние БД (outbox)               |
| **TC-NEW-15** | ТЗ 05 (outbox retry → FAILED)        | integration      | Негативный | RabbitMQ недоступен, `maxRetryCount` достигнут                    | Вызвать `processSingleEvent()` повторно                                                | Событие помечено `FAILED`, retryCount ≥ maxRetryCount                                                                 | Состояние БД (retry)                |
| **TC-NEW-16** | ТЗ 04 (reversal резервирования)      | integration      | Негативный | APPROVED-транзакция с резервированием                             | `POST /api/internal/rollback` с rrn                                                    | HTTP 200, `status=APPROVED`, резервирование откатано, `reservations_rollbacks` содержит запись                        | Сценарий (reversal)                 |
| **TC-NEW-17** | ТЗ 04 (reversal conflict)            | integration      | Негативный | Транзакция уже откатана                                           | Повторный `POST /api/internal/rollback` с тем же rrn                                   | HTTP 409 Conflict, `declineReason=ALREADY_ROLLED_BACK`                                                                | Состояние БД (идемпотентность)      |
| **TC-NEW-18** | ТЗ 01 (E2E happy path)               | e2e              | Позитивный | Все сервисы подняты, карта ACTIVE                                 | Terminal → Gateway → Switch → Authorization → CMS → Logger                             | HTTP 200 APPROVED, `responseCode=00`; через Awaitility запись в Logger                                                | Сценарий (сквозной)                 |
| **TC-NEW-19** | ТЗ 01 (E2E declined)                 | e2e              | Негативный | Все сервисы подняты, карта BLOCKED                                | Terminal → Gateway → Switch → Authorization                                            | HTTP 200, `status=DECLINED`, `declineReason=CARD_BLOCKED`; запись в Logger                                            | Сценарий (сквозной)                 |
| **TC-NEW-20** | ТЗ 01 (E2E reversal)                 | e2e              | Негативный | Все сервисы подняты, RabbitMQ останавливается в момент публикации | Terminal → Gateway → Switch → Authorization → (сбой RMQ)                               | DECLINED `responseCode=96`, резервирование откатано                                                                   | Сценарий (сквозной)                 |

**Покрытие требований части (б):**

- уровни: `unit` (TC-NEW-01..07), `api` (TC-NEW-08..11), `integration` (TC-NEW-12..17), `e2e` (TC-NEW-18..20) — **4 уровня**;
- **sync-путь:** валидация (TC-NEW-05/06), маршрутизация по BIN (TC-NEW-08), лимиты/баланс (TC-NEW-02/04), CRUD (TC-NEW-10), резервирование (TC-NEW-07/09);
- **async-путь:** доставка в Logger (TC-NEW-12), publisher-confirm + reversal (TC-NEW-13/16/20), outbox → Notification (TC-NEW-14/15), eventual consistency (TC-NEW-12/18).

---

## Часть (в): распределение тест-кейсов практики 2

### Основные ТК (TC-01..TC-18)

| ID (пр. 2) | Название                          | Что проверяет тест                      | Уровень | Обоснование                                                                      |
| ---------- | --------------------------------- | --------------------------------------- | ------- | -------------------------------------------------------------------------------- |
| TC-01      | APPROVED для ACTIVE-карты         | Логика решения + генерация RRN/authCode | unit    | Чистая логика `AuthServiceImpl` с моками клиентов; контракт проверяется отдельно |
| TC-02      | DECLINED CARD_INACTIVE            | Ветка статуса в `authorize()`           | unit    | Изолированная логика выбора `DeclineOutcome`                                     |
| TC-03      | DECLINED CARD_BLOCKED             | Ветка статуса в `authorize()`           | unit    | Изолированная логика выбора `DeclineOutcome`                                     |
| TC-04      | DECLINED CARD_EXPIRED (54)        | Ветка статуса EXPIRED                   | unit    | Изолированная логика                                                             |
| TC-05      | DECLINED по сроку (0826)          | Проверка `expiryDate`                   | unit    | Граничное значение, чистая логика                                                |
| TC-06      | APPROVED на границе лимита (8000) | Граница дневного лимита                 | unit    | Граничное значение, логика `checkAndUpdateLimits`                                |
| TC-07      | DECLINED 61 (8001)                | Превышение дневного лимита              | unit    | Граничное значение                                                               |
| TC-08      | DECLINED 61 (25001)               | Превышение месячного лимита             | unit    | Граничное значение                                                               |
| TC-09      | DECLINED 51 (20001)               | Недостаток средств                      | unit    | Граничное значение баланса                                                       |
| TC-10      | DECLINED 05 (CMS недоступен)      | Обработка `ServiceUnavailableException` | unit    | Мок HTTP-клиента, проверка ветки отказа                                          |
| TC-11      | CMS create → 201                  | Контракт создания карты                 | api     | Проверка HTTP-контракта `POST /api/cards`                                        |
| TC-12      | CMS read → 404                    | Контракт чтения карты                   | api     | Проверка HTTP-контракта `GET /api/cards/{pan}`                                   |
| TC-13      | CMS update → 200                  | Контракт обновления                     | api     | Проверка HTTP-контракта `PATCH /api/cards/{pan}`                                 |
| TC-14      | CMS delete → 204                  | Контракт удаления                       | api     | Проверка HTTP-контракта `DELETE /api/cards/{pan}`                                |
| TC-15      | CMS create невалидный BIN → 400   | Валидация BIN                           | api     | Проверка валидации на границе API                                                |
| TC-16      | CMS generate → 201 (95/3/2)       | Генерация карт                          | api     | Проверка контракта `POST /api/cards/generate`                                    |
| TC-17      | Reserve → 200                     | Контракт резервирования                 | api     | Проверка HTTP-контракта `POST /api/cards/{pan}/reserve`                          |
| TC-18      | Reserve > balance → 402           | Граница резервирования                  | api     | Проверка контракта + граничное значение                                          |

### Pairwise ТК Authorization (TC-PW-AUTH-01..25)

| ID (пр. 2)        | Название                     | Что проверяет тест               | Уровень | Обоснование                                        |
| ----------------- | ---------------------------- | -------------------------------- | ------- | -------------------------------------------------- |
| TC-PW-AUTH-01..08 | Отказы по статусу/сроку      | Комбинации статус × срок         | unit    | Логика выбора `DeclineOutcome`, изолируется моками |
| TC-PW-AUTH-09..19 | Отказы по лимитам/балансу    | Комбинации лимит × баланс × срок | unit    | Логика порядка проверок, чистая логика             |
| TC-PW-AUTH-20     | APPROVED (equal/below/equal) | Успешная авторизация             | unit    | Чистая логика                                      |
| TC-PW-AUTH-21     | DECLINED 54 (expired)        | Приоритет срока над балансом     | unit    | Логика порядка проверок                            |
| TC-PW-AUTH-22     | DECLINED CARD_BLOCKED        | Ветка статуса                    | unit    | Изолированная логика                               |
| TC-PW-AUTH-23     | DECLINED 51                  | Недостаток средств               | unit    | Граничное значение                                 |
| TC-PW-AUTH-24     | APPROVED                     | Успешная авторизация             | unit    | Чистая логика                                      |
| TC-PW-AUTH-25     | DECLINED CARD_INACTIVE       | Ветка статуса                    | unit    | Изолированная логика                               |

### Pairwise ТК Card-Management (TC-PW-CM-01..26)

| ID (пр. 2)  | Название                           | Что проверяет тест        | Уровень | Обоснование              |
| ----------- | ---------------------------------- | ------------------------- | ------- | ------------------------ |
| TC-PW-CM-01 | delete → 204                       | Контракт удаления         | api     | HTTP-контракт            |
| TC-PW-CM-02 | read wrong_length → 400            | Валидация длины PAN       | api     | Валидация на границе API |
| TC-PW-CM-03 | reserve equal → 200                | Резервирование на границе | api     | Контракт + граница       |
| TC-PW-CM-04 | create невалидный BIN+expiry → 400 | Двойная валидация         | api     | Валидация на границе API |
| TC-PW-CM-05 | create пустой expiry → 400         | Валидация формата         | api     | Валидация на границе API |
| TC-PW-CM-06 | read invalid_luhn → 400            | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-07 | delete invalid_luhn → 400          | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-08 | update wrong_length → 400          | Валидация длины           | api     | Валидация на границе API |
| TC-PW-CM-09 | create unknown BIN → 400           | Валидация BIN             | api     | Валидация на границе API |
| TC-PW-CM-10 | update invalid_luhn → 400          | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-11 | read DELETED → 404                 | Карта удалена             | api     | Контракт + состояние БД  |
| TC-PW-CM-12 | generate → 201 (95/3/2)            | Генерация                 | api     | Контракт                 |
| TC-PW-CM-13 | reserve above → 402                | Превышение баланса        | api     | Контракт + граница       |
| TC-PW-CM-14 | generate → 201 (2% BLOCKED)        | Распределение статусов    | api     | Контракт                 |
| TC-PW-CM-15 | create valid → 201                 | Создание карты            | api     | Контракт                 |
| TC-PW-CM-16 | update invalid_luhn → 400          | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-17 | generate → 201                     | Генерация                 | api     | Контракт                 |
| TC-PW-CM-18 | generate → 201 (3% INACTIVE)       | Распределение статусов    | api     | Контракт                 |
| TC-PW-CM-19 | update wrong_length → 400          | Валидация длины           | api     | Валидация на границе API |
| TC-PW-CM-20 | delete wrong_length → 400          | Валидация длины           | api     | Валидация на границе API |
| TC-PW-CM-21 | reserve below → 200                | Резервирование            | api     | Контракт                 |
| TC-PW-CM-22 | read wrong_length → 400            | Валидация длины           | api     | Валидация на границе API |
| TC-PW-CM-23 | update invalid_luhn → 400          | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-24 | update valid → 200                 | Обновление карты          | api     | Контракт                 |
| TC-PW-CM-25 | delete invalid_luhn → 400          | Валидация Луна            | api     | Валидация на границе API |
| TC-PW-CM-26 | update valid → 200                 | Обновление карты          | api     | Контракт                 |

---

## Часть (г): анализ пропорций

### Сводная таблица

| Уровень           | Старые (пр. 2) | Новые (часть б) |  Итого |  Доля |
| ----------------- | -------------: | --------------: | -----: | ----: |
| unit              |             35 |               7 |     42 | 47.2% |
| api / integration |             34 |              10 |     44 | 49.4% |
| e2e               |              0 |               3 |      3 |  3.4% |
| **Итого**         |         **69** |          **20** | **89** |  100% |

### Фактические пропорции

- **unit:** 42 / 89 = **47.2%**
- **api / integration:** 44 / 89 = **49.4%**
- **e2e:** 3 / 89 = **3.4%**

### Объяснение отклонений

**1. Совпадает ли пирамида с ориентиром 70/20/10?**
Нет. У нас unit ≈ 47%, api/integration ≈ 49%, e2e ≈ 3%. Это осознанное смещение, а не ошибка.
Ориентир 70/20/10 — для монолитов с толстым слоем чистой логики. В нашем случае значительная часть проверок — это **контракты** (валидация PAN/BIN/expiry, коды ответов, границы лимитов), которые адекватно проверяются только на уровне API, а не unit.

**2. Почему в enterprise-микросервисах доля api/integration часто выше 20%?**
Потому что в микросервисах **плотные контракты** и **риск интеграций**:

- каждый сервис — отдельный процесс, контракт между ними (HTTP/JSON, AMQP) — самостоятельный объект тестирования (TC-NEW-08..11, TC-11..18, TC-PW-CM-\*);
- валидация входных данных (`@Pan`, `@Bin`, `@Rrn`) живёт на границе API
  ([`CardController`](../../services/card-management/src/main/java/com/processing/cardmanagement/controllers/CardController.java)),
  и её нельзя полноценно проверить unit-тестом сервиса;
- асинхронные связи (RabbitMQ) требуют интеграционных проверок доставки, retry, DLQ (TC-NEW-12..17) — их нет в монолите.

**3. Почему e2e мало и какие сценарии туда попали?**
E2E дорогие и медленные, поэтому туда попали только **критичные сквозные сценарии**:

- happy path (TC-NEW-18) — основной бизнес-поток;
- declined (TC-NEW-19) — ключевой отказ;
- reversal (TC-NEW-20) — самый рискованный сценарий (сбой публикации + откат).
  Всё остальное (границы, валидация, отдельные ветки) проверяется на нижних уровнях.

**4. Нет ли риска ice-cream cone или дефицита контрактных проверок?**

- **Ice-cream cone (перекос в E2E):** нет — e2e всего 3.4%, основная масса проверок на unit/api.
- **Дефицит контрактных проверок:** нет — api/integration ≈ 49%, что покрывает все точки
  сочленения из Container-диаграммы ([`A2-container.md`](architecture/A2-container.md)).
- **Риск:** доля unit (47%) ниже ориентира, но это компенсируется тем, что чистая логика
  (`AuthServiceImpl`, `CardServiceImpl`, `PanGenerator`, `LuhnValidator`) покрыта плотно, а «толстые» контракты вынесены на api-уровень.
