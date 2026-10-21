# А3. C4 Level 3 — Component (Authorization, Card-Management)

> **Что было в проекте:** компонентного уровня нет вообще — в документации нет схемы «что внутри сервиса».
> **Что сделано:** построены Component-диаграммы для двух ключевых сервисов, читая пакеты [`services/authorization/...`](../../../services/authorization/src/main/java/com/processing/authorization/) и [`services/card-management/...`](../../../services/card-management/src/main/java/com/processing/cardmanagement/).
> Показано: controller → service → client/repository, где HTTP-клиенты, где JDBC, где outbox.

## A3.1 Authorization Service

```mermaid
C4Component
    title C4 Level 3 — Component: Authorization Service

    Container_Boundary(auth, "Authorization Service") {
        Component(ctrl, "AuthControllerImpl", "REST Controller", "POST /api/internal/authorize, /api/internal/rollback; маппинг declineReason → HTTP-статус")
        Component(svc, "AuthServiceImpl", "Service", "Бизнес-логика: проверка статуса/срока/баланса/лимитов, RRN, authCode, reversal")
        Component(notifier, "AuthorizationEventNotifierImpl", "Event Notifier", "Внутренняя событийная модель (лог-события)")
        Component(binClient, "BinLookupClient", "HTTP Client", "GET /api/bin/{bin} — обогащение issuerId, graceful degradation")
        Component(cmsClient, "CardManagementClientImpl", "HTTP Client", "GET card, POST reserve, POST rollback к CMS")
        Component(limitRepo, "LimitUsageRepository", "Repository (JDBC)", "upsertLimitUsage, fetchRrnBlock — таблица limit_usage")
        Component(cleanup, "CleanupServiceImpl", "Service", "Очистка устаревших записей лимитов")
    }

    ContainerDb(db, "PostgreSQL", "PostgreSQL 16", "limit_usage")
    Container(cms, "Card Management", "Spring Boot", "CMS")
    Container(bin, "Bin Lookup", "Spring Boot", "BIN API")

    Rel(ctrl, svc, "authorize() / rollback()", "Java")
    Rel(svc, notifier, "notify(event)", "Java")
    Rel(svc, binClient, "getIssuerId(pan)", "Java")
    Rel(svc, cmsClient, "getCard / reserve / rollback", "Java")
    Rel(svc, limitRepo, "upsertLimitUsage / fetchRrnBlock", "Java")
    Rel(cleanup, limitRepo, "delete old", "Java")
    Rel(binClient, bin, "GET /api/bin/{bin}", "HTTP sync")
    Rel(cmsClient, cms, "GET/POST", "HTTP sync")
    Rel(limitRepo, db, "JDBC", "JDBC sync")
```

**Ключевые точки (для unit-тестов):**

- `AuthServiceImpl.authorize()` — чистая бизнес-логика, изолируется моками `BinLookupClient`,
  `CardManagementClient`, `LimitUsageRepository` ([`AuthServiceImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java:43-176)).
- Порядок проверок: статус → срок → баланс → лимиты → резервирование
  ([`AuthServiceImpl.java`](../../../services/authorization/src/main/java/com/processing/authorization/services/AuthServiceImpl.java:72-170)).
- `DeclineOutcome` — единая точка кодов отказа ([`DeclineOutcome.java`](../../../services/authorization/src/main/java/com/processing/authorization/constants/DeclineOutcome.java)).
- HTTP-клиенты (`BinLookupClient`, `CardManagementClientImpl`) — кандидаты на mock в unit и на контракт в API-тестах.
- `LimitUsageRepository` — реальная БД, тестируется интеграционно.

## A3.2 Card-Management Service

```mermaid
C4Component
    title C4 Level 3 — Component: Card Management Service

    Container_Boundary(cms, "Card Management Service") {
        Component(cardCtrl, "CardController", "REST Controller", "POST/GET/PATCH/DELETE /api/cards")
        Component(genCtrl, "CardGeneratorController", "REST Controller", "POST /api/cards/generate")
        Component(binCtrl, "BinIssuerController", "REST Controller", "Справочник BIN → issuerId")
        Component(cardSvc, "CardServiceImpl", "Service", "CRUD, reserve, rollback, bulkUpdateStatus")
        Component(genSvc, "CardGeneratorService", "Service", "Генерация карт, распределение статусов 95/3/2")
        Component(panGen, "PanGenerator", "Service", "Генерация PAN по Луну")
        Component(luhn, "LuhnValidator", "Service", "Проверка контрольной суммы")
        Component(outboxProc, "OutboxEventProcessorImpl", "Outbox Processor", "Публикация PENDING-событий в RabbitMQ, retry, FAILED")
        Component(notifier, "CardEventNotifierImpl", "Event Notifier", "Внутренние события → outbox")
        Component(cardRepo, "CardRepository", "Repository (JDBC)", "cards")
        Component(resRepo, "ReservationRepository", "Repository (JDBC)", "reservations")
        Component(rollRepo, "ReservationRollbackRepository", "Repository (JDBC)", "reservations_rollbacks")
        Component(outboxRepo, "OutboxRepository", "Repository (JDBC)", "outbox_events")
    }

    ContainerDb(db, "PostgreSQL", "PostgreSQL 16", "cards, reservations, outbox_events")
    ContainerQueue(rmq, "RabbitMQ", "RabbitMQ 3.13", "smp.card-events")

    Rel(cardCtrl, cardSvc, "create/get/patch/delete/reserve/rollback", "Java")
    Rel(genCtrl, genSvc, "generate(count, bins)", "Java")
    Rel(binCtrl, cardSvc, "getIssuerId", "Java")
    Rel(cardSvc, panGen, "generatePan(bin)", "Java")
    Rel(panGen, luhn, "validate", "Java")
    Rel(cardSvc, notifier, "notifyListeners(event)", "Java")
    Rel(notifier, outboxProc, "save(event) → outbox", "Java")
    Rel(cardSvc, cardRepo, "create/findByPan/update", "Java")
    Rel(cardSvc, resRepo, "save/findByRrnAndPan", "Java")
    Rel(cardSvc, rollRepo, "save", "Java")
    Rel(outboxProc, outboxRepo, "save/processSingleEvent", "Java")
    Rel(outboxProc, rmq, "convertAndSend card.*", "AMQP async")
    Rel(cardRepo, db, "JDBC", "JDBC sync")
    Rel(resRepo, db, "JDBC", "JDBC sync")
    Rel(rollRepo, db, "JDBC", "JDBC sync")
    Rel(outboxRepo, db, "JDBC", "JDBC sync")
```

**Ключевые точки (для unit-тестов):**

- `CardServiceImpl` — бизнес-логика CRUD/reserve/rollback, изолируется моками репозиториев
  ([`CardServiceImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/CardServiceImpl.java)).
- `PanGenerator` + `LuhnValidator` — чистая логика, идеальна для unit.
- `OutboxEventProcessorImpl` — отдельный компонент публикации, тестируется изолированно
  ([`OutboxEventProcessorImpl.java`](../../../services/card-management/src/main/java/com/processing/cardmanagement/services/OutboxEventProcessorImpl.java:32-54)).
- Репозитории — реальная БД, тестируются интеграционно.

## Чего не хватало в проекте и что улучшено

| Чего не хватало                                     | Что сделано                                                       |
| --------------------------------------------------- | ----------------------------------------------------------------- |
| Компонентного уровня нет                            | Построены Component-диаграммы для Authorization и Card-Management |
| Не показано, где HTTP-клиенты, где JDBC, где outbox | Явно размечены типы компонентов и связи                           |
| Не видно порядка проверок в authorize()             | Отражён порядок: статус → срок → баланс → лимиты → резерв         |
