# А1. C4 Level 1 — Context (границы СМП и внешние акторы)

> **Что было в проекте:** отдельной Context-диаграммы нет. В [`docs/architecture.md`](../../../docs/architecture.md:5-59) есть общая mermaid-схема, но границы «СМП vs внешний мир» явно не проведены.
> **Что сделано:** построена Context-диаграмма, где СМП — единый «чёрный ящик», а снаружи перечислены акторы и протоколы их входа

## Диаграмма

```mermaid
C4Context
    title C4 Level 1 — Context: Симулятор процессингового центра (СМП)

    Person(terminal, "POS-терминал", "Эмулируется Terminal Simulator. Инициирует авторизацию покупки")
    Person(merchant, "Мерчант / Эквайрер", "Эмулируется Merchant Acquirer. Передаёт MCC, комиссию")
    Person(dashboardUser, "Пользователь дашборда", "Оператор/аналитик. Смотрит KPI, графики, транзакции")

    System(smp, "СМП — Симулятор процессингового центра", "Микросервисный процессинг: маршрутизация, авторизация, резервирование, логирование, уведомления")

    System_Ext(binLookup, "Внешний BIN Lookup", "Обогащение issuerId по BIN (первые 6 цифр PAN)")

    Rel(terminal, smp, "POST /api/transactions (HTTP/JSON, упрощённый ISO 8583)", "HTTPS/JSON")
    Rel(merchant, smp, "POST /api/transactions (HTTP/JSON)", "HTTPS/JSON")
    Rel(dashboardUser, smp, "REST /api/dashboard/**, WebSocket /ws/transactions", "HTTPS + WS")
    Rel(smp, binLookup, "GET /api/bin/{bin} (HTTP, sync, 3s connect / 5s read)", "HTTP/JSON")
```

## Акторы и протоколы входа

| Актор                 | Что это                | Протокол входа   | Точка входа в СМП                                      | Evidence (код)                                                                                                                                                                                                                                                                            |
| --------------------- | ---------------------- | ---------------- | ------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| POS-терминал          | Эмулятор терминала     | HTTP POST JSON   | Gateway `POST /api/transactions`                       | [`TerminalSimulatorController`](../../../services/terminal-simulator/src/main/java/com/processing/terminalsimulator/controller/TerminalSimulatorController.java), [`application.yml`](../../../services/gateway/src/main/resources/application.yml:65-73)                                 |
| Мерчант / Эквайрер    | Эмулятор мерчанта      | HTTP POST JSON   | Gateway `POST /api/transactions`                       | [`MerchantAcquirerClient`](../../../services/switch/src/main/java/com/processing/service/MerchantAcquirerClient.java), [`application.yml`](../../../services/gateway/src/main/resources/application.yml:92-97)                                                                            |
| Пользователь дашборда | Оператор React SPA     | REST + WebSocket | Gateway `GET /api/dashboard/**`, `WS /ws/transactions` | [`DashboardController`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/controller/DashboardController.java), [`WebSocketConfig`](../../../services/transaction-logger/src/main/java/com/processing/transactionlogger/websocket/WebSocketConfig.java) |
| Внешний BIN Lookup    | Внешний API обогащения | HTTP GET (sync)  | Authorization → `GET /api/bin/{bin}`                   | [`BinLookupClient`](../../../services/authorization/src/main/java/com/processing/authorization/client/BinLookupClient.java:51-69)                                                                                                                                                         |

## Границы системы

**Внутри СМП (область тестирования):** Gateway, Card Management, Switch, Authorization,
Bin Lookup (как отдельный сервис-эмулятор), Terminal Simulator, Merchant Acquirer,
Transaction Logger, Notification Service, Web Dashboard, PostgreSQL, RabbitMQ.

**Снаружи (эмулируется или мокается):**

- POS-терминал и мерчант — эмулируются сервисами `terminal-simulator` и `merchant-acquirer`;
- внешний BIN Lookup — в СМП реализован как отдельный сервис `bin-lookup`, но по контракту
  играет роль внешней системы (Authorization вызывает его синхронно и деградирует при недоступности).

## Чего не хватало в проекте и что улучшено

| Чего не хватало                                   | Что сделано                                             |
| ------------------------------------------------- | ------------------------------------------------------- |
| Отдельной Context-диаграммы нет                   | Построена C4 Context с явными границами СМП             |
| Не перечислены акторы и протоколы входа           | Таблица акторов с протоколами и ссылками на код         |
| Не показан внешний BIN Lookup как внешняя система | Вынесен за границу СМП, показан sync-вызов с таймаутами |
