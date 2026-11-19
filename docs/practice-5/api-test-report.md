# API-тесты СМП — отчёт

> **Модуль:** 5 — API-тестирование REST-сервисов (артефакт 5, часть 1 из 2).
> **Объект:** СМП — Симулятор процессингового центра (11 сервисов, PostgreSQL, RabbitMQ, единая точка входа Gateway).
> **Стек тестов:** Java 21, Maven, JUnit 5, REST-assured 5.4.0, Hamcrest, Awaitility 4.2.2, JSON Schema Validator.
> **Модуль тестов:** [`services/api-tests`](../../services/api-tests) (профиль Maven `api-tests`).
> **Команда запуска:** `docker compose up -d`, затем `make api-test` (или
`mvn -f services/pom.xml -Papi-tests -pl api-tests test`).
> **CI:** лёгкая джоба `api-tests-compile` в [`ci.yml`](../../.github/workflows/ci.yml) (checkstyle + компиляция),
> тяжёлая джоба `api-tests` в [`practice-run.yml`](../../.github/workflows/practice-run.yml) (docker compose + прогон).
> **Источники контракта:** [`docs/api-spec.md`](../api-spec.md), контроллеры сервисов, маршруты [
`services/gateway/src/main/resources/application.yml`](../../services/gateway/src/main/resources/application.yml).

---

## 1. Покрытие

### 1.1. Сводка по эндпоинтам

Счётчик «Кол-во проверок» — число тестовых методов/параметризованных вызовов в модуле `api-tests`, относящихся к
эндпоинту.

| Эндпоинт                                             | Проверяемые статус-коды                 | Кол-во проверок | Статус                                                                 |
|------------------------------------------------------|-----------------------------------------|:---------------:|------------------------------------------------------------------------|
| `GET /health` (Gateway)                              | 200                                     |        2        | покрыт; 503 — только при недоступной зависимости (см. 5.3)             |
| `POST /api/cards`                                    | 201, 400, 404                           |       10        | покрыт; 409 (коллизия PAN) недостижим детерминированно                 |
| `POST /api/cards/generate`                           | 201, 400                                |        4        | покрыт                                                                 |
| `GET /api/cards/{pan}`                               | 200, 400, 404                           |        6        | покрыт (включая X-Cache и удалённую карту)                             |
| `GET /api/cards` (список)                            | 200, 400, 413                           |        4        | покрыт                                                                 |
| `PATCH /api/cards/{pan}`                             | 200, 400, 404                           |        4        | покрыт                                                                 |
| `DELETE /api/cards/{pan}`                            | 204, 400, 404                           |        4        | покрыт; повторное удаление — 404 (карта скрыта `@SQLRestriction`)      |
| `POST /api/cards/{pan}/reserve`                      | 200, 400, 402, 404, 409                 |        6        | покрыт                                                                 |
| `POST /api/cards/{pan}/rollback`                     | 200, 404, 409                           |        3        | покрыт; 400 — в `InternalEndpointsApiTest` и валидации тела            |
| `POST /api/cards/bulk-update`                        | 200, 400                                |        5        | покрыт                                                                 |
| `POST /api/transactions`                             | 200 (APPROVED/DECLINED), 400, 429       |       48        | покрыт (включая позитив, отказы, валидацию, 429, async-эффект)         |
| `GET /api/transactions` (неизвестный путь)           | 404                                     |        1        | покрыт                                                                 |
| `GET /api/transactions/search`                       | 200, 400                                |       15        | покрыт                                                                 |
| `GET /api/transactions/export`                       | 200, 400                                |        2        | покрыт, проверены `Content-Disposition` и `Content-Type`               |
| `GET /api/dashboard/stats`                           | 200                                     |        1        | покрыт                                                                 |
| `GET /api/dashboard/recent`                          | 200, 400                                |        4        | покрыт                                                                 |
| `GET /api/dashboard/charts`                          | 200, 400                                |        4        | покрыт                                                                 |
| `POST /api/simulator/terminal/run`                   | 200, 400                                |        3        | покрыт; 422/502/503 — только при сбоях зависимостей                    |
| `POST /api/simulator/terminal/start-continuous`      | 200, 400, 422                           |        2        | покрыт                                                                 |
| `POST /api/simulator/terminal/stop`                  | 200                                     |        1        | покрыт                                                                 |
| `POST /api/simulator/merchant/run`                   | 200, 400                                |        3        | покрыт; 404/502 — только при отсутствии данных/сбое Gateway            |
| `GET /api/simulator/merchants`                       | 200 (напрямую, 8084); 404 через Gateway |        2        | покрыт; маршрут Gateway не совпадает (DEF-12)                          |
| `POST /api/simulator/merchant/fee`                   | 400, 404                                |        2        | покрыт; 200 требует корреляции с ранее сохранённой комиссией (см. 5.3) |
| `POST /api/internal/route` (Switch, 8082)            | 200 (APPROVED/DECLINED), 400            |        4        | покрыт                                                                 |
| `POST /api/internal/authorize` (Authorization, 8083) | 200, 400, 403, 404, 422                 |        6        | покрыт; 503 — только при недоступном Card Management                   |
| `POST /api/internal/rollback` (Authorization, 8083)  | 200, 400, 404, 409                      |        5        | покрыт                                                                 |
| `POST /api/internal/log` (Logger, 8088)              | 201, 200, 400, 409                      |       15        | покрыт                                                                 |
| `GET /api/internal/log` (Logger, 8088)               | 405                                     |        1        | покрыт                                                                 |

**Итого:** 167 включённых проверок + 6 disabled-проб расхождений = 173 теста в прогоне (JUnit считает каждый
параметризованный вызов отдельным тестом).

### 1.2. Перечень эндпоинтов из трёх источников и расхождения

| Возможность                                              | `docs/api-spec.md` | Контроллеры / код |                    Маршруты Gateway                     | Вывод                                                                                  |
|----------------------------------------------------------|:------------------:|:-----------------:|:-------------------------------------------------------:|----------------------------------------------------------------------------------------|
| `GET /health`                                            |         да         |        да         |                  локальный контроллер                   | совпадает                                                                              |
| `POST /api/cards`                                        |         да         |        да         |            `/api/cards/**` → card-management            | совпадает                                                                              |
| `POST /api/cards/generate`                               |         да         |        да         |                     `/api/cards/**`                     | совпадает                                                                              |
| `GET /api/cards/{pan}`                                   |         да         |        да         |                     `/api/cards/**`                     | совпадает                                                                              |
| `GET /api/cards` (список)                                |      **нет**       |        да         |                     `/api/cards/**`                     | DEF-06: недокументированный публичный эндпоинт                                         |
| `PATCH/DELETE/reserve/rollback/bulk-update`              |      **нет**       |        да         |                     `/api/cards/**`                     | DEF-06: недокументированные публичные эндпоинты                                        |
| `GET /api/bins/**`                                       |        нет         |        да         |                    **нет маршрута**                     | вне области: недоступен через Gateway и не участвует в цепочке авторизации/логирования |
| `POST /api/transactions`                                 |         да         | да (через Switch) |    `POST /api/transactions` → `/api/internal/route`     | внешний и внутренний путь различаются (rewrite)                                        |
| `GET /api/transactions/search`                           |         да         |        да         |             `/api/transactions/**` → logger             | совпадает, фильтры шире спецификации (DEF-06)                                          |
| `GET /api/transactions/export`                           |      **нет**       |        да         |                 `/api/transactions/**`                  | DEF-06: недокументированный публичный эндпоинт                                         |
| `GET /api/dashboard/stats`, `/recent`                    |         да         |        да         |                   `/api/dashboard/**`                   | совпадает                                                                              |
| `GET /api/dashboard/charts`                              |      **нет**       |        да         |                   `/api/dashboard/**`                   | DEF-06: недокументированный публичный эндпоинт                                         |
| `POST /api/simulator/terminal/run`                       |         да         |        да         |              `/api/simulator/terminal/**`               | совпадает                                                                              |
| `POST /api/simulator/terminal/start-continuous`, `/stop` |      **нет**       |        да         |              `/api/simulator/terminal/**`               | DEF-06                                                                                 |
| `POST /api/simulator/merchant/run`                       |         да         |        да         |              `/api/simulator/merchant/**`               | совпадает                                                                              |
| `POST /api/simulator/merchant/fee`                       |      **нет**       |        да         |              `/api/simulator/merchant/**`               | DEF-06                                                                                 |
| `GET /api/simulator/merchants`                           |      **нет**       |        да         | `/api/simulator/merchant/**` не совпадает с `merchants` | DEF-06 + DEF-12 (эндпоинт недоступен через Gateway)                                    |
| `POST /api/internal/route`                               |         да         |        да         |           rewrite из `POST /api/transactions`           | совпадает                                                                              |
| `POST /api/internal/authorize`                           |         да         |        да         |                  напрямую (порт 8083)                   | совпадает                                                                              |
| `POST /api/internal/rollback`                            |      **нет**       |        да         |                  напрямую (порт 8083)                   | DEF-06                                                                                 |
| `POST /api/internal/log`                                 |         да         |        да         |                  напрямую (порт 8088)                   | совпадает; статус повтора расходится (DEF-04)                                          |
| `WS /ws/transactions`                                    |         да         |        да         |                    напрямую к Logger                    | вне области модуля 5 (WebSocket — см. 5.3)                                             |

---

## 2. Бизнес-отказы

Исходы перечисления `DeclineOutcome` (Authorization) и `RollbackResponse` (откат). Для каждого исхода проверяется пара
«доменный код + причина», потому что часть исходов имеет одинаковый ISO-код (05).

| Исход                   | Код | Причина                 | Путь достижения                                          | Способ подготовки данных                                                                 | Статус                                                                                   |
|-------------------------|:---:|-------------------------|----------------------------------------------------------|------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `CARD_NOT_FOUND`        | 14  | `CARD_NOT_FOUND`        | неизвестный PAN (BIN зарегистрирован)                    | PAN не создавался (`unusedPan()`)                                                        | покрыт (Gateway + internal authorize 404)                                                |
| `CARD_NOT_FOUND`        | 14  | `CARD_NOT_FOUND`        | неизвестный BIN в Switch                                 | PAN с BIN `999999`                                                                       | покрыт (Switch формирует отказ, mti=0110 — DEF-02)                                       |
| `CARD_BLOCKED`          | 05  | `CARD_BLOCKED`          | состояние карты                                          | `PATCH /api/cards/{pan}` → `status=BLOCKED`                                              | покрыт (Gateway 200 DECLINED; internal 403)                                              |
| `CARD_INACTIVE`         | 05  | `CARD_INACTIVE`         | состояние карты                                          | `PATCH` → `status=INACTIVE`                                                              | покрыт                                                                                   |
| `CARD_EXPIRED`          | 54  | `CARD_EXPIRED`          | **два пути:** состояние карты и данные запроса           | `PATCH` → `EXPIRED`; либо `transmissionDateTime=2035-01-01` при сроке действия +3 года   | покрыт (оба пути, совпадение кода/причины проверено)                                     |
| `EXCEEDS_AMOUNT_LIMIT`  | 61  | `EXCEEDS_AMOUNT_LIMIT`  | **два пути:** уменьшение дневного лимита и сумма запроса | `PATCH dailyLimit=100000`, amount=150000; либо карта с dailyLimit=100000 и amount=150000 | покрыт (оба пути)                                                                        |
| `INSUFFICIENT_FUNDS`    | 51  | `INSUFFICIENT_FUNDS`    | **два пути:** баланс карты и сумма запроса               | баланс 10000 при amount=20000; либо amount=60000000 при балансе 50000000                 | покрыт (оба пути; internal 422)                                                          |
| `SERVICE_UNAVAILABLE`   | 96  | `SERVICE_UNAVAILABLE`   | недоступен Card Management                               | остановить `smp-card-management`                                                         | **не автоматизирован**: требует управления контейнерами (см. 5.3); ручной сценарий в 5.4 |
| `RESERVATION_FAILED`    | 96  | `RESERVATION_FAILED`    | исключение при резервировании                            | сбой CMS во время reserve                                                                | не автоматизирован (инфраструктурный отказ)                                              |
| `UNKNOWN_REASON`        | 05  | `UNKNOWN_REASON`        | непредусмотренное исключение                             | инъекция сбоя                                                                            | не автоматизирован (инфраструктурный отказ)                                              |
| `DB_UNAVAILABLE`        | 96  | `DB_UNAVAILABLE`        | недоступна БД `limit_usage`                              | остановить PostgreSQL                                                                    | не автоматизирован (модуль 6 — БД в контейнерах)                                         |
| `TRANSACTION_NOT_FOUND` | 14  | `TRANSACTION_NOT_FOUND` | откат по неизвестному RRN                                | валидный RRN, которого нет в БД                                                          | покрыт (internal rollback 404)                                                           |
| `ALREADY_ROLLED_BACK`   | 05  | `ALREADY_ROLLED_BACK`   | повторный откат                                          | reserve → rollback → rollback                                                            | покрыт (409)                                                                             |
| `ROLLBACK_FAILED`       | 96  | `ROLLBACK_FAILED`       | сбой CMS при откате                                      | остановить Card Management                                                               | не автоматизирован (инфраструктурный отказ)                                              |

Проверки отказов через Gateway подтверждают правило лекции 10: клиент всегда видит **200 с телом DECLINED**, а
внутренний сервис различает 403/404/422/503.

---

## 3. Валидация

| Поле / правило                                                                                                     | Где проверяется                       | Классы эквивалентности и границы                             |    Кол-во проверок    |
|--------------------------------------------------------------------------------------------------------------------|---------------------------------------|--------------------------------------------------------------|:---------------------:|
| 11 обязательных полей `AuthorizationRequest`                                                                       | Gateway `TransactionRequestValidator` | каждое поле отсутствует (`null`)                             | 11 (параметризованно) |
| `mti` = `0100`                                                                                                     | Gateway                               | корректное, `0200`                                           |           2           |
| `pan` = 16 цифр                                                                                                    | Gateway                               | пусто, 3 цифры, 15 цифр, 17 цифр, буква                      |           5           |
| `amount` > 0                                                                                                       | Gateway                               | 0, −1, положительное                                         |           3           |
| `currencyCode` = 3 символа                                                                                         | Gateway                               | 2, 4, 3                                                      |           2           |
| `mcc` = 4 цифры                                                                                                    | Gateway                               | 3, 5, буквы, корректное                                      |           3           |
| Тело — валидный JSON                                                                                               | Gateway                               | пустое/битое тело                                            |           1           |
| `Amount`, `Stan`, `Pan`, `ProcessingCode`, `CurrencyCode`, `Mcc`, `TerminalId`, `MerchantId`, `Status`, `Id`, даты | Logger `TransactionRequest`           | `null`, неверная длина, неверный шаблон, 0                   | 7 (параметризованно)  |
| `bin` = 6 цифр, зарегистрирован                                                                                    | Card Management                       | 5 цифр, `null`, неизвестный BIN `999999`                     |           3           |
| `cardholderName`                                                                                                   | Card Management                       | нижний регистр, `null`                                       |           2           |
| `currencyCode`                                                                                                     | Card Management                       | 2 символа, буквы                                             |           2           |
| `dailyLimit`/`monthlyLimit` ≥ 0                                                                                    | Card Management                       | −1 (создание), −1 (PATCH), daily > monthly                   |           3           |
| `count` ≥ 1, `bins` не пуст, `count` ≤ 10000                                                                       | Card Generator                        | 0, пустой список, 10001                                      |           3           |
| `limit`/`offset` списка карт                                                                                       | Card Management                       | 0, 1, 2, 10000, 10001, −1                                    |           4           |
| `amount` > 0, `rrn` = 12 цифр                                                                                      | Reserve                               | 0, `123`, корректные                                         |           2           |
| `rrn`/`pan`/`amount` отката                                                                                        | Rollback                              | `1`, 0, корректные                                           |           2           |
| `limit` 1..500, `offset` ≥ 0, `dateFrom ≤ dateTo`                                                                  | Transaction Logger                    | 0, 1, 500, 501, −1, перевёрнутый диапазон                    |           7           |
| `limit` recent 1..500                                                                                              | Dashboard                             | 0, 1, 501                                                    |           3           |
| `granularity ∈ {hour, day}`, `from < to`                                                                           | Dashboard charts                      | `day`, `week`, перевёрнутый диапазон                         |           3           |
| `count` ≥ 1, `scenario` обязателен, `tps` ≥ 1                                                                      | Симуляторы                            | 0, отсутствующий `scenario`, отсутствующий `transactionType` |           4           |
| Bean validation `AuthorizationRequest`                                                                             | Authorization (internal)              | отсутствует `issuerId`, `terminalId` длины 2                 |           2           |

Тело ошибки проверяется для каждого нарушения Gateway, Logger, Card Management и Merchant Acquirer:
`error`, `message`, `serviceName`, `retryAfterMs` — по схеме [
`error-response.json`](../../services/api-tests/src/test/resources/schemas/error-response.json).
У Authorization формат тела ошибки отличается (DEF-08) — там проверяется только статус.

---

## 4. Расхождения контракта и реализации

| ID     | Декларировано                                                                                              | Наблюдается                                                                                                                                                                                                                                             | Источник декларации                                                                                      | Классификация и значимость                                                                                                                                                                                                                                                              |
|--------|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| DEF-01 | `mti` ответа авторизации = `0110`                                                                          | `0100` (эхо запроса)                                                                                                                                                                                                                                    | `docs/api-spec.md`, модель `AuthorizationResponse`; код `AuthorizationResponse.approved/declined`        | **дефект спецификации**: код консистентен с ISO-8583 (эхо MTI), но потребитель, читающий `mti`, получит не то, что обещано. Проба: `approvedMtiShouldBe0110PerSpecification`                                                                                                            |
| DEF-02 | Единый формат ответа авторизации                                                                           | Отказ по неизвестному BIN даёт `mti=0110`, отказы Authorization — `0100`                                                                                                                                                                                | код `AuthorizationResponse.unknownBin` vs `declined`                                                     | **дефект реализации**: два пути одного контракта возвращают разные MTI. Проба: `unknownBinDeclineUsesFixedMti` (зелёная фиксация)                                                                                                                                                       |
| DEF-03 | Gateway валидирует длины `stan`, `processingCode`, `terminalId`, `merchantId` (String(6/6/8/15))           | Проверяется только непустота; строка `stan=123` проходит Gateway                                                                                                                                                                                        | `docs/api-spec.md` (AuthorizationRequest) vs `TransactionRequestValidator`                               | **дефект реализации**: неполная валидация; ошибка контракта превращается в 200 DECLINED 05 «Authorization service unavailable», что скрывает причину. Пробы: `shortStanPassesGatewayValidation` (фиксация), `gatewayShouldRejectStanOfWrongLength`                                      |
| DEF-04 | `POST /api/internal/log` → 201 (`{id,status}`)                                                             | Первая публикация 201, повтор — 200 с полным `TransactionResponse`                                                                                                                                                                                      | `docs/api-spec.md` раздел 7 vs `InternalTransactionController`                                           | **дефект спецификации**: поведение корректно для идемпотентности, документ не описывает 200. Проба: `loggerReplayShouldReturn201PerSpecification`                                                                                                                                       |
| DEF-05 | —                                                                                                          | —                                                                                                                                                                                                                                                       | —                                                                                                        | **снято в ходе работы**: предположение о 400 при чтении удалённой карты не подтвердилось экспериментально. `CardEntity` имеет `@SQLRestriction("status <> 'DELETED'")`, поэтому чтение и повторное удаление дают 404. Тесты `deletedCardIsNotVisible`, `deleteCardTwiceReturnsNotFound` |
| DEF-06 | Спецификация перечисляет эндпоинты и параметры поиска                                                      | Реализованы и доступны через Gateway: список/PATCH/DELETE/reserve/rollback/bulk-update карт, CSV-экспорт, charts, start-continuous/stop, fee, internal rollback; поиск поддерживает `issuerId` и `mcc`; фильтра `stan` нет ни в спецификации, ни в коде | `docs/api-spec.md` vs контроллеры и `TransactionFilter`                                                  | **дефект спецификации**: контракт неполон; часть возможностей вообще не описана. Покрыты как источник «контроллеры»                                                                                                                                                                     |
| DEF-07 | Внутренний `POST /api/internal/route` принимает `AuthorizationRequest`                                     | Пустое тело `{}` → 200 DECLINED 14 (нет валидации)                                                                                                                                                                                                      | `docs/api-spec.md` раздел 7 vs `RouteController`                                                         | **дефект реализации**: внутренний контракт не валидируется, ошибки клиента маскируются бизнес-отказом. Проба: `switchRouteDoesNotValidateBody` (фиксация)                                                                                                                               |
| DEF-08 | Ошибки валидации описываются `ErrorResponse`; `@ApiResponses` для 400 ссылается на `AuthorizationResponse` | Стандартное тело Spring (`timestamp/status/error/path`), поля `ErrorResponse` отсутствуют                                                                                                                                                               | `docs/api-spec.md`, аннотации `AuthControllerImpl` vs отсутствие `@RestControllerAdvice` в authorization | **дефект реализации**: формат ошибок не соответствует единому контракту сервисов. Проба: `internalAuthorizationValidationErrorShouldUseErrorResponse`                                                                                                                                   |
| DEF-09 | После `DELETE` карта недоступна                                                                            | Gateway не инвалидирует кеш на 204: `GET /api/cards/{pan}` может вернуть устаревшую карту из кеша (`X-Cache: HIT`, TTL 5 минут)                                                                                                                         | `ResponseCachingGatewayFilterFactory` (очистка только для 200/201)                                       | **дефект реализации, высокий риск**: клиент видит удалённую карту. Проба: `deleteShouldInvalidateGatewayCache`                                                                                                                                                                          |
| DEF-10 | Неподдерживаемый метод → 405                                                                               | 500 (общий `@ExceptionHandler(Exception.class)` перехватывает `HttpRequestMethodNotSupportedException`)                                                                                                                                                 | REST-семантика vs `card-management` `GlobalExceptionHandler`                                             | **дефект реализации, низкая значимость**: неверный контракт обработки ошибок. Проба: `unsupportedMethodShouldReturnMethodNotAllowed`                                                                                                                                                    |
| DEF-11 | Повторная доставка сообщения идемпотентна                                                                  | `TransactionMapper.matches` сравнивает `Instant` точно, PostgreSQL хранит `timestamp(6)`; метка с наносекундами после записи не равна исходной → повтор того же сообщения даёт 409                                                                      | код `TransactionService`/`TransactionMapper` vs требование идемпотентности                               | **дефект реализации, средний риск**: ложный конфликт повторной доставки. Тест `replayWithSubMicrosecondPrecisionConflicts` фиксирует поведение; основные тесты идемпотентности используют точность до миллисекунд                                                                       |
| DEF-12 | `GET /api/simulator/merchants` доступен через Gateway                                                      | 404: маршрут `/api/simulator/merchant/**` не совпадает с сегментом `merchants`; эндпоинт доступен только напрямую на 8084                                                                                                                               | маршруты Gateway vs `SimulationController`                                                               | **дефект реализации (маршрутизация)**: часть API Merchant Acquirer недоступна через единую точку входа. Тесты `merchantsListIsNotExposedByGateway`, `merchantsListIsAvailable`                                                                                                          |

Все пробы находятся в [
`ContractDivergenceProbeTest`](../../services/api-tests/src/test/java/com/processing/apitests/ContractDivergenceProbeTest.java)
и помечены `@Disabled` с ID расхождения: сьют остаётся зелёным, но каждая проба становится красной при снятии
`@Disabled`, то есть расхождение проверяемо, а не «подогнано» под реализацию.

**Какое поведение считать правильным.** Для DEF-01/DEF-04/DEF-06 источником истины признан код и ISO-8583
(спецификация — дефектна), поэтому тесты проверяют наблюдаемое поведение, а расхождение оформлено как дефект
документа. Для DEF-03/DEF-07/DEF-08/DEF-09/DEF-10/DEF-11/DEF-12 источником истины признан контракт (код
дефектен), поэтому тесты фиксируют текущее поведение, а проба или отдельный тест формулирует ожидаемое по
контракту.

---

## 5. Обоснование достаточности

### 5.1. Выделенные классы входных данных

1. **Авторизация, позитив:** валидный запрос, карта ACTIVE, достаточно средств и лимитов.
2. **Авторизация, бизнес-отказы:** 7 классов, достижимых в здоровом стенде (`CARD_NOT_FOUND` по PAN и по BIN,
   `CARD_BLOCKED`, `CARD_INACTIVE`, `CARD_EXPIRED`, `EXCEEDS_AMOUNT_LIMIT`, `INSUFFICIENT_FUNDS`); для трёх из них
   проверены оба пути достижения (состояние карты / данные запроса).
3. **Авторизация, нарушение контракта:** 11 обязательных полей + 6 правил формата/границ; каждый класс — отдельный
   запрос с единственным нарушением.
4. **Карты:** создание, чтение, список, изменение, удаление, reserve/rollback/bulk-update, генерация пачки.
   Классы: валидные значения, неверный формат, неизвестный BIN, несуществующая карта, повторная операция,
   недостаток средств, границы лимитов пагинации.
5. **Логирование:** новая запись, повтор с теми же данными, конфликт данных, валидация полей, метод не разрешён.
6. **Поиск:** каждый параметр фильтра отдельно + комбинации `pan+status`, `pan+mcc`, `pan+issuerId`,
   `pan+declineReason`, `pan+dateFrom/dateTo`; пагинация (0/1/2/500/501/−1); пустая выборка.
7. **Нефункциональное:** лимит частоты (100/с на IP), ожидание асинхронного эффекта и ожидание его отсутствия.
8. **Сквозная цепочка:** Gateway → Switch → Authorization → RabbitMQ → Logger → поиск.

### 5.2. Чем подтверждена полнота

- **По эндпоинтам:** перечень построен из трёх источников (спецификация, контроллеры, маршруты Gateway);
  расхождения сведены в таблицу 1.2 и раздел 4. Каждый маршрут Gateway имеет тест.
- **По статус-кодам:** для каждого эндпоинта основания — код обработки (`@ExceptionHandler`, `ResponseEntity.status`)
  и раздел ошибок спецификации; покрыты все коды, достижимые в здоровом стенде детерминированно.
- **По бизнес-отказам:** перечисление `DeclineOutcome` (13 констант) разобрано построчно; 9 покрыты тестами,
  4 инфраструктурных вынесены с обоснованием (5.3).
- **По валидации:** по каждому правилу выделены классы эквивалентности и границы (таблица раздела 3);
  параметризованные тесты покрывают несколько представителей одним методом, но каждый представитель — отдельный
  запрос с единственным нарушением (локализация причины).
- **По параметрам:** все параметры фильтрации и пагинации покрыты; комбинации — попарно (см. 5.1, п. 6);
  пустая выборка проверена отдельно (200 и `total=0`).
- **По повторной доставке:** все три исхода (201/200/409) + проверка «запись ровно одна» GPath-фильтром по `id`.
- **По асинхронности:** появление эффекта (Awaitility, 15 с / 500 мс / 1 с) и отсутствие эффекта
  (`during(4s)`), фиксированных пауз нет.
- **Сквозная цепочка:** один тест проверяет ответ Gateway и затем запись в Logger с теми же `stan`, `status`,
  `amount`, `rrn`, `authCode`, `issuerId`.

### 5.3. Что осознанно оставлено вне покрытия и почему

| Что                                                                                                                 | Причина                                                                                                                                                                 |
|---------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 503/500/502, возникающие только при остановке зависимости (Authorization 503, Logger 503, Switch 503, Merchant 502) | Требуют управления контейнерами из теста и делают прогон невоспроизводимым; инфраструктурные отказы — предмет модуля 6 (Testcontainers/WireMock). Ручной сценарий — 5.4 |
| `SERVICE_UNAVAILABLE`, `RESERVATION_FAILED`, `UNKNOWN_REASON`, `DB_UNAVAILABLE`, `ROLLBACK_FAILED`                  | Требуют инъекции отказа внутрь сервисов; описаны как ручной сценарий                                                                                                    |
| 409 конфликта PAN при создании карты                                                                                | PAN генерируется случайно; детерминированно коллизию не воспроизвести, а прямой доступ к БД запрещён условием                                                           |
| `POST /api/simulator/merchant/fee` → 200                                                                            | Ответ не содержит `transmissionDateTime/terminalId`, поэтому нельзя построить запрос к ранее сохранённой комиссии; проверены 400 и 404                                  |
| `/api/bins/**`                                                                                                      | Нет маршрута Gateway и не участвует в цепочке авторизации/логирования — вне области                                                                                     |
| `WS /ws/transactions`                                                                                               | WebSocket не входит в синхронные HTTP-контракты модуля 5                                                                                                                |
| Контроль доступа 401/403/сценарии OAuth2                                                                            | В СМП контроля доступа нет (осознанное упрощение, лекция 10)                                                                                                            |
| PostgreSQL/RabbitMQ в контейнерах, DLQ, TTL                                                                         | Модуль 6                                                                                                                                                                |

### 5.4. Ручной сценарий для инфраструктурных отказов

1. `docker stop smp-card-management` → `POST /api/transactions` с валидной картой: ожидается 200, `DECLINED`,
   `responseCode=96`, `declineReason=SERVICE_UNAVAILABLE`; `docker start smp-card-management`.
2. `docker stop smp-rabbitmq` → `POST /api/transactions` по карте с достаточным балансом: ожидается 200,
   `DECLINED`, `responseCode=96`, `declineReason=System error`; резервирование откатывается;
   `docker start smp-rabbitmq`.
3. `docker stop smp-postgres` → `POST /api/internal/log`: ожидается 503 `Database operation failed` с
   `retryAfterMs="1000"`; `docker start smp-postgres`.

---

## 6. Доказательства работоспособности проверок

### 6.1. Намеренные поломки (мутации)

Каждая мутация вносится в код сервиса, образ пересобирается, запускается целевой класс тестов, затем изменение
откатывается. Колонка «Результат» фиксирует фактический прогон (см. 6.2).

| #  | Тест                                                                        | Внесённое изменение                                                                                                             | Результат                                                                                                                                                                                                                                    |
|:--:|-----------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| M1 | `TransactionValidationApiTest`                                              | `TransactionRequestValidator`: regex PAN `\\d{16}` → `\\d{15}`                                                                  | **красный**: 10 падений из 28 (PAN, currencyCode, mcc, amount, `shortStanPassesGatewayValidation`); например, `invalidPanClassesAreRejected` — ожидался 400, получен 200                                                                     |
| M2 | `TransactionRateLimitApiTest#exceedingRateLimitReturnsTooManyRequests`      | `TransactionRateLimitFilter`: `Retry-After` `"1"` → `"2"`                                                                       | **красный**: `expected: <1> but was: <2>`                                                                                                                                                                                                    |
| M3 | `LoggerIdempotencyApiTest`                                                  | `TransactionService.existingTransactionResult`: условие `if (!matches)` заменено на `if (matches)` — принятие повтора отключено | **красный**: 5 падений из 16 (`replayReturnsExistingTransaction` 200→409, `conflictingReplayReturnsConflict` 409→200, `redeliveryStoresOnlyOneRecord`, `replayStatusDiffersFromSpecification`, `replayWithSubMicrosecondPrecisionConflicts`) |
| M4 | `InternalEndpointsApiTest#internalAuthorizationBlockedCardReturnsForbidden` | `AuthControllerImpl`: `CARD_BLOCKED` → `HttpStatus.BAD_REQUEST`                                                                 | **красный**: `expected status code <403> but was <400>`                                                                                                                                                                                      |

После каждой мутации изменение откатывалось, образ сервиса пересобирался, контейнер пересоздавался.

### 6.2. Результаты прогонов

Все прогоны выполнены на локально поднятом стеке (`docker compose up -d`, все сервисы healthy).

| Прогон                  | Команда                                                                      | Итог                                                                       |
|-------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------|
| Зелёный прогон №1       | `mvn -f services/pom.xml -Papi-tests -pl api-tests test`                     | BUILD SUCCESS: 173 теста, 0 failures, 0 errors, 6 skipped (disabled-пробы) |
| Зелёный прогон №2       | то же                                                                        | BUILD SUCCESS: 173 / 0 / 0 / 6                                             |
| Зелёный прогон №3       | `... test -Dapi.run=3`                                                       | BUILD SUCCESS: 173 / 0 / 0 / 6                                             |
| M1                      | `... -Dtest=TransactionValidationApiTest` после мутации                      | BUILD FAILURE: 10 failures / 28                                            |
| M2                      | `... -Dtest=TransactionRateLimitApiTest` после мутации                       | BUILD FAILURE: 1 failure / 2                                               |
| M3                      | `... -Dtest=LoggerIdempotencyApiTest` после мутации                          | BUILD FAILURE: 5 failures / 16                                             |
| M4                      | `... -Dtest=InternalEndpointsApiTest,LoggerIdempotencyApiTest` после мутации | BUILD FAILURE: 1 failure / 31 (только blocked-card тест)                   |
| Восстановление после M4 | `... test -Dapi.run=final`                                                   | BUILD SUCCESS: 173 / 0 / 0 / 6 — откат мутаций подтверждён                 |

### 6.3. Мутационная проверка теста идемпотентности

Сценарий M3 — это и есть требуемая проверка «проверки проверок»: отключение дедупликации делает тест
идемпотентности красным, значит тест действительно проверяет дедупликацию, а не факт ответа.

---

## 7. Порядок запуска

### 7.1. Локально

```bash
cp .env.example .env          # Windows: copy /Y .env.example .env
docker compose up -d
# дождаться health: curl http://localhost:8080/health
make api-test                 # или: mvn -f services/pom.xml -Papi-tests -pl api-tests test
```

Отчёты Surefire: `services/api-tests/target/surefire-reports/`.

Переопределение адресов (необязательно): `SMP_GATEWAY_URL`, `SMP_SWITCH_URL`, `SMP_AUTH_URL`, `SMP_LOGGER_URL`,
`SMP_MERCHANT_URL` (или системные свойства `smp.gateway.url` и т. д.).

### 7.2. CI

1. **Лёгкий пайплайн** `ci.yml`, джоба `api-tests-compile`: checkstyle + `test-compile` (стек не нужен), запускается
   на каждый push.
2. **Тяжёлый пайплайн** `practice-run.yml`: Actions → Run workflow → `practice=api`: сборка образов,
   `docker compose up -d`,
   ожидание health, `mvn -Papi-tests -pl api-tests test`, выгрузка Surefire-отчётов, очистка.

### 7.3. Изоляция и воспроизводимость

- Каждый тест создаёт свою карту и уникальные `stan`/`rrn`/`id`; общих константных идентификаторов нет.
- Состояние системы меняется только через API (без прямого доступа к БД).
- Асинхронные проверки используют Awaitility с явными границами; фиксированных пауз нет.
- Rate-limit тест использует синтетический `X-Forwarded-For`, поэтому не расходует bucket других тестов.
- Карта кеша Gateway уникальна для каждого PAN; при проверке `X-Cache` запись и чтение выполняются в одном тесте.
- Расхождения с недостижимыми в здоровом стенде статусами вынесены в disabled-пробы и ручные сценарии (5.4).
