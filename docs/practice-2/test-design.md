# Артефакт 2 - тест-дизайн

---

## 1. Классы эквивалентности

### 1.1 Authorization

| Поле / условие            | Класс                           | Валидность | Пример значения | Ожидаемый результат              |
| ------------------------- | ------------------------------- | :--------: | :-------------: | -------------------------------- |
| Статус карты              | ACTIVE                          |     Да     |     ACTIVE      | Перейти к проверке срока         |
| Статус карты              | INACTIVE                        |    Нет     |    INACTIVE     | DECLINED, CARD_INACTIVE          |
| Статус карты              | BLOCKED                         |    Нет     |     BLOCKED     | DECLINED, CARD_BLOCKED           |
| Статус карты              | EXPIRED                         |    Нет     |     EXPIRED     | DECLINED, код 54                 |
| Срок действия             | expiryDate $\geq$ текущий месяц |     Да     |  текущий MMYY   | Следующая проверка               |
| Срок действия             | expiryDate < текущий месяц      |    Нет     | предыдущий MMYY | DECLINED, код 54                 |
| Сумма vs dailyLimit       | amount $\leq$ остаток           |     Да     |      1000       | Возможен APPROVED                |
| Сумма vs dailyLimit       | amount > остаток                |    Нет     |    остаток+1    | DECLINED, код 61                 |
| Сумма vs monthlyLimit     | amount $\leq$ остаток           |     Да     |      1000       | Возможен APPROVED                |
| Сумма vs monthlyLimit     | amount > остаток                |    Нет     |    остаток+1    | DECLINED, код 61                 |
| Сумма vs availableBalance | amount $\leq$ balance           |     Да     |      1000       | Возможен APPROVED                |
| Сумма vs availableBalance | amount > balance                |    Нет     |    balance+1    | DECLINED, код 51                 |
| Доступность CMS           | Доступен                        |     Да     |    HTTP 200     | Продолжить                       |
| Доступность CMS           | Недоступен                      |    Нет     |     timeout     | DECLINED, код 05, ISSUER_TIMEOUT |

### 1.2. Card Management

| Поле / условие       | Класс                          | Валидность |        Пример значения        | Ожидаемый результат               |
| -------------------- | ------------------------------ | :--------: | :---------------------------: | --------------------------------- |
| operation            | create                         |     Да     |        POST /api/cards        | 201 Created, status=ACTIVE        |
| operation            | read                           |     Да     |     GET /api/cards/{pan}      | 200 OK                            |
| operation            | read                           |     Да     | GET /api/cards/{invalid_pan}  | 404 Not Found                     |
| operation            | update                         |     Да     |    PATCH /api/cards/{pan}     | 200 OK, поля обновлены            |
| operation            | delete                         |     Да     |    DELETE /api/cards/{pan}    | 204 No Content, status=DELETED    |
| operation            | reserve                        |     Да     | POST /api/cards/{pan}/reserve | 200 OK, balance уменьшен          |
| operation            | generate                       |     Да     |   POST /api/cards/generate    | 201 Created, распределение 95/3/2 |
| PAN                  | Валиден по Луну, 16 цифр       |     Да     |       4000001234560001        | Карта создана                     |
| PAN                  | Невалиден по Луну              |    Нет     |       4000001234560002        | Ошибка валидации                  |
| PAN                  | Длина ≠ 16                     |    Нет     |         400000123456          | Ошибка валидации                  |
| bin                  | Валидный (400000–400004)       |     Да     |            400000             | Корректная генерация PAN          |
| bin                  | Невалидный (нет в таблице)     |    Нет     |            999999             | Ошибка валидации                  |
| expiryDate           | MMYY, +3 года                  |     Да     |             0929              | Карта создана                     |
| expiryDate           | Неверный формат                |    Нет     |            2029-09            | Ошибка валидации                  |
| status при генерации | ACTIVE (95%)                   |     Да     |            ACTIVE             | Карта активна                     |
| status при генерации | INACTIVE (3%)                  |     Да     |           INACTIVE            | Карта неактивна                   |
| status при генерации | BLOCKED (2%)                   |     Да     |            BLOCKED            | Карта заблокирована               |
| status после delete  | DELETED                        |     Да     |            DELETED            | Карта не возвращается в GET       |
| count при генерации  | 1 $\leq$ count $\leq$ maxCount |     Да     |              100              | 100 карт                          |
| count при генерации  | count > maxCount               |    Нет     |          maxCount+1           | CardGenerationLimitException      |
| reserve amount       | amount $\leq$ balance          |     Да     |             1000              | 200, balance уменьшен             |
| reserve amount       | amount > balance               |    Нет     |           balance+1           | 402, balance не изменён           |
| rrn при reserve      | Уникален для (rrn, pan)        |     Да     |           новый rrn           | 200                               |

## 2. Граничные значения (далее Boundary Value/BV)

Границы разделены на два вида: **amount vs limit** (сумма относительно самого лимита) и **limit usage** (сумма с учётом уже потраченного за период).

| ID    | Поле                       | Предусловие                          | Границы (ON/OFF)      | Ожидаемый результат                          |
| ----- | -------------------------- | ------------------------------------ | --------------------- | -------------------------------------------- |
| BV-01 | amount vs dailyLimit       | dailyLimit=10000                     | 9999 / 10000 / 10001  | 9999, 10000 → APPROVED; 10001 → DECLINED/61  |
| BV-02 | amount vs monthlyLimit     | monthlyLimit=30000                   | 29999 / 30000 / 30001 | 29999, 30000 → APPROVED; 30001 → DECLINED/61 |
| BV-03 | amount vs availableBalance | balance=20000                        | 19999 / 20000 / 20001 | 19999, 20000 → APPROVED; 20001 → DECLINED/51 |
| BV-04 | expiryDate                 | текущий 09/2026                      | 0826 / 0926 / 1026    | 0826 → DECLINED/54; 0926, 1026 → проходят    |
| BV-05 | dailyLimit usage           | dailyUsed=2000, dailyLimit=10000     | 7999 / 8000 / 8001    | 7999, 8000 → APPROVED; 8001 → DECLINED/61    |
| BV-06 | monthlyLimit usage         | monthlyUsed=5000, monthlyLimit=30000 | 24999 / 25000 / 25001 | 24999, 25000 → APPROVED; 25001 → DECLINED/61 |
| BV-07 | PAN длина                  | —                                    | 15 / 16 / 17          | 16 → валиден; 15, 17 → ошибка                |
| BV-08 | reserve amount             | balance=20000                        | 19999 / 20000 / 20001 | 19999, 20000 → 200; 20001 → 402              |

## 3. Попарное тестирование

Для ядра построены две независимые модели PICT — по одной на каждый сервис.

### 3.1. Authorization

- **Файл модели:** `docs/practice-2/pict/model.txt` (7 параметров, 2 ограничения).
- **Сгенерированный набор:** `docs/practice-2/pict/cases.txt` (25 строк).

| Параметр          | Значения                                 |
| ----------------- | ---------------------------------------- |
| card_status       | ACTIVE, INACTIVE, BLOCKED, EXPIRED       |
| amount_vs_daily   | below, equal, above                      |
| amount_vs_monthly | below, equal, above                      |
| amount_vs_balance | below, equal, above                      |
| expiry            | valid, current_month, expired            |
| terminal_type     | pos, atm, ecom                           |
| mcc               | grocery, restaurant, electronics, travel |

**Ограничения:**

```pict
IF [card_status] <> "ACTIVE" THEN [amount_vs_daily] = "below"
    AND [amount_vs_monthly] = "below" AND [amount_vs_balance] = "below";
IF [expiry] = "expired" THEN [amount_vs_balance] = "below";
```

**Обоснование ограничений:**

- **Ограничение 1** (не-ACTIVE статус → все суммы below). При статусе, отличном от ACTIVE, транзакция отклоняется по причине статуса карты; проверка лимитов и баланса на результат не влияет. Сочетания с превышением лимитов для таких карт недостижимы как значимые. Ограничение исключает недостижимые сочетания и не тратит строки набора.
- **Ограничение 2** (expiry=expired → balance below). При истёкшем сроке транзакция отклоняется по сроку действия до проверки баланса. Сочетание «истёкший срок + превышение баланса» недостижимо.

### 3.2. Card-Management

- **Файл модели:** `docs/practice-2/pict/model-card-management.txt` (6 параметров, 6 ограничений).
- **Сгенерированный набор:** `docs/practice-2/pict/cases-card-management.txt` (26 строк).

| Параметр          | Значения                                        |
| ----------------- | ----------------------------------------------- |
| operation         | create, read, update, delete, reserve, generate |
| pan_validity      | valid, invalid_luhn, wrong_length               |
| bin               | known, unknown                                  |
| expiry_format     | mmyy, iso, empty                                |
| status            | ACTIVE, INACTIVE, BLOCKED, DELETED              |
| amount_vs_balance | below, equal, above                             |

**Ограничения:**

```pict
IF [operation] = "create" THEN [pan_validity] = "valid" AND [amount_vs_balance] = "below";
IF [operation] = "generate" THEN [pan_validity] = "valid" AND [amount_vs_balance] = "below";
IF [operation] = "read" THEN [amount_vs_balance] = "below";
IF [operation] = "delete" THEN [amount_vs_balance] = "below";
IF [operation] = "reserve" THEN [pan_validity] = "valid" AND [bin] = "known" AND [expiry_format] = "mmyy";
IF [status] = "DELETED" THEN [operation] <> "reserve";
```

**Обоснование ограничений:**

- **Ограничения 1–2** (create/generate → PAN валиден, баланс не проверяется). При создании и генерации PAN формируется сервисом по алгоритму Луна, поэтому невалидный PAN в этих операциях недостижим. Баланс при создании задаётся, а не сравнивается с суммой.
- **Ограничения 3–4** (read/delete → баланс не проверяется). Чтение и удаление не оперируют суммой транзакции.
- **Ограничение 5** (reserve → PAN валиден, BIN известен, формат MMYY). Резервирование вызывается Authorization по уже существующей карте, поэтому PAN заведомо валиден, BIN известен, а срок хранится в формате MMYY.
- **Ограничение 6** (DELETED → не reserve). Удалённая карта не участвует в транзакциях (ТЗ 05, п. 2).

## 4. Тест-кейсы

| ID        | Источник         |    Вид     | Требование          | Предусловие                                   | Шаги                                                                              | Ожидаемый результат                                                                           |
| :-------- | :--------------- | :--------: | :------------------ | :-------------------------------------------- | :-------------------------------------------------------------------------------- | :-------------------------------------------------------------------------------------------- |
| **TC-01** | КЭ: status       | Позитивный | ТЗ 04, п. 2         | Карта ACTIVE, срок валиден, баланс 20000      | POST `/api/internal/authorize`, amount=1000                                       | HTTP 200: APPROVED, `responseCode="00"`, сгенерирован уникальный RRN (12 цифр) и authCode     |
| **TC-02** | КЭ: status       | Негативный | ТЗ 04, п. 2         | Карта INACTIVE                                | POST `/api/internal/authorize`, amount=1000                                       | HTTP 403: DECLINED, причина `CARD_INACTIVE`                                                   |
| **TC-03** | КЭ: status       | Негативный | ТЗ 04, п. 2         | Карта BLOCKED                                 | POST `/api/internal/authorize`, amount=1000                                       | HTTP 403: DECLINED, причина `CARD_BLOCKED`                                                    |
| **TC-04** | КЭ: status       | Негативный | ТЗ 04, п. 2         | Карта EXPIRED                                 | POST `/api/internal/authorize`, amount=1000                                       | HTTP 403: DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`                               |
| **TC-05** | BV-04            | Негативный | ТЗ 04, п. 2 (шаг 3) | Карта ACTIVE, expiryDate=0826 (прошлый месяц) | POST `/api/internal/authorize`, amount=1000                                       | HTTP 403: DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`                               |
| **TC-06** | BV-01            | Позитивный | ТЗ 04, п. 4         | Карта ACTIVE, остаток суточного лимита 8000   | POST `/api/internal/authorize`, amount=8000                                       | HTTP 200: APPROVED, `responseCode="00"`, суточный лимит исчерпан полностью                    |
| **TC-07** | BV-01            | Негативный | ТЗ 04, п. 4         | Карта ACTIVE, остаток суточного лимита 8000   | POST `/api/internal/authorize`, amount=8001                                       | HTTP 400: DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT`                       |
| **TC-08** | BV-02            | Негативный | ТЗ 04, п. 4         | Карта ACTIVE, остаток месячного лимита 25000  | POST `/api/internal/authorize`, amount=25001                                      | HTTP 400: DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT`                       |
| **TC-09** | BV-03            | Негативный | ТЗ 04, п. 2 (шаг 6) | Карта ACTIVE, баланс = 20000                  | POST `/api/internal/authorize`, amount=20001                                      | HTTP 422: DECLINED, `responseCode="51"`, причина `INSUFFICIENT_FUNDS`                         |
| **TC-10** | КЭ: CMS          | Негативный | ТЗ 04, п. 5         | CMS остановлен / имитация таймаута            | POST `/api/internal/authorize`, amount=1000                                       | HTTP 503: DECLINED, `responseCode="05"`, причина `SERVICE_UNAVAILABLE`                        |
| **TC-11** | КЭ: CMS create   | Позитивный | ТЗ 05, п. 2         | База данных CMS доступна                      | POST `/api/cards` с валидными параметрами BIN 400000                              | HTTP 201 Created: сгенерирован 16-значный PAN с корректным Луном, срок +3 года, статус ACTIVE |
| **TC-12** | КЭ: CMS read     | Негативный | ТЗ 05, п. 2         | База данных CMS доступна                      | GET `/api/cards/4000001234567890` (16 цифр, валиден по формату, отсутствует в БД) | HTTP 404 Not Found                                                                            |
| **TC-13** | КЭ: CMS update   | Позитивный | ТЗ 05, п. 2         | Карта ACTIVE создана                          | PATCH `/api/cards/{pan}` с новым `availableBalance`                               | HTTP 200 OK: поле обновлено, остальные не изменены                                            |
| **TC-14** | КЭ: CMS delete   | Позитивный | ТЗ 05, п. 2         | Карта ACTIVE создана                          | DELETE `/api/cards/{pan}`                                                         | HTTP 204 No Content: статус карты = DELETED, карта не возвращается в GET                      |
| **TC-15** | КЭ: CMS bin      | Негативный | ТЗ 05, п. 2         | База данных CMS доступна                      | POST `/api/cards` с `bin=999999` (нет в таблице BIN)                              | HTTP 400 Bad Request: ошибка валидации BIN                                                    |
| **TC-16** | КЭ: CMS generate | Позитивный | ТЗ 05, п. 3         | База данных CMS доступна                      | POST `/api/cards/generate`, body: `{count: 100, bins: ["400000"]}`                | HTTP 201 Created: 100 карт, распределение статусов ≈ 95/3/2                                   |
| **TC-17** | КЭ: Reserve      | Позитивный | ТЗ 05, п. 5         | Карта существует, баланс 20000                | POST `/api/cards/{pan}/reserve`, body: `{amount: 1000, rrn: "123456789012"}`      | HTTP 200 OK: доступный баланс уменьшен до 19000                                               |
| **TC-18** | BV-08            | Негативный | ТЗ 05, п. 5         | Карта существует, баланс 20000                | POST `/api/cards/{pan}/reserve`, body: `{amount: 20001, rrn: "123456789013"}`     | HTTP 402 Payment Required: `ErrorResponse`, недостаточно средств для резервирования           |

### 4.1. Тест-кейсы из попарного набора Authorization (pairwise)

Проецирование 25 сгенерированных строк `cases.txt` в тест-кейсы Authorization Service.

| ID                | № стр. | Предусловие (Status / Daily / Monthly / Balance / Expiry) | Шаги (Terminal / MCC)                             | Ожидаемый результат                                           |
| :---------------- | :----: | :-------------------------------------------------------- | :------------------------------------------------ | :------------------------------------------------------------ |
| **TC-PW-AUTH-01** |   1    | EXPIRED, below, below, below, valid                       | POST `/api/internal/authorize`, pos, restaurant   | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-02** |   2    | BLOCKED, below, below, below, current_month               | POST `/api/internal/authorize`, atm, grocery      | DECLINED, причина `CARD_BLOCKED`                              |
| **TC-PW-AUTH-03** |   3    | INACTIVE, below, below, below, expired                    | POST `/api/internal/authorize`, ecom, grocery     | DECLINED, причина `CARD_INACTIVE` (статус первичен)           |
| **TC-PW-AUTH-04** |   4    | INACTIVE, below, below, below, valid                      | POST `/api/internal/authorize`, atm, travel       | DECLINED, причина `CARD_INACTIVE`                             |
| **TC-PW-AUTH-05** |   5    | EXPIRED, below, below, below, current_month               | POST `/api/internal/authorize`, ecom, electronics | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-06** |   6    | BLOCKED, below, below, below, expired                     | POST `/api/internal/authorize`, pos, electronics  | DECLINED, причина `CARD_BLOCKED`                              |
| **TC-PW-AUTH-07** |   7    | EXPIRED, below, below, below, current_month               | POST `/api/internal/authorize`, pos, grocery      | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-08** |   8    | EXPIRED, below, below, below, expired                     | POST `/api/internal/authorize`, atm, travel       | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-09** |   9    | ACTIVE, equal, above, above, valid                        | POST `/api/internal/authorize`, ecom, travel      | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-10** |   10   | ACTIVE, above, equal, equal, current_month                | POST `/api/internal/authorize`, pos, travel       | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-11** |   11   | ACTIVE, equal, equal, below, expired                      | POST `/api/internal/authorize`, atm, electronics  | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-12** |   12   | ACTIVE, above, above, equal, valid                        | POST `/api/internal/authorize`, atm, grocery      | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-13** |   13   | ACTIVE, below, equal, above, current_month                | POST `/api/internal/authorize`, atm, restaurant   | DECLINED, `responseCode="51"`, причина `INSUFFICIENT_FUNDS`   |
| **TC-PW-AUTH-14** |   14   | ACTIVE, above, above, above, valid                        | POST `/api/internal/authorize`, pos, electronics  | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-15** |   15   | INACTIVE, below, below, below, current_month              | POST `/api/internal/authorize`, ecom, restaurant  | DECLINED, причина `CARD_INACTIVE`                             |
| **TC-PW-AUTH-16** |   16   | ACTIVE, equal, above, equal, current_month                | POST `/api/internal/authorize`, ecom, restaurant  | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-17** |   17   | ACTIVE, below, above, below, expired                      | POST `/api/internal/authorize`, pos, electronics  | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-18** |   18   | BLOCKED, below, below, below, valid                       | POST `/api/internal/authorize`, ecom, travel      | DECLINED, причина `CARD_BLOCKED`                              |
| **TC-PW-AUTH-19** |   19   | ACTIVE, above, equal, above, valid                        | POST `/api/internal/authorize`, ecom, grocery     | DECLINED, `responseCode="61"`, причина `EXCEEDS_AMOUNT_LIMIT` |
| **TC-PW-AUTH-20** |   20   | ACTIVE, equal, below, equal, current_month                | POST `/api/internal/authorize`, pos, grocery      | APPROVED, `responseCode="00"`                                 |
| **TC-PW-AUTH-21** |   21   | ACTIVE, above, below, below, expired                      | POST `/api/internal/authorize`, pos, restaurant   | DECLINED, `responseCode="54"`, причина `CARD_EXPIRED`         |
| **TC-PW-AUTH-22** |   22   | BLOCKED, below, below, below, current_month               | POST `/api/internal/authorize`, pos, restaurant   | DECLINED, причина `CARD_BLOCKED`                              |
| **TC-PW-AUTH-23** |   23   | ACTIVE, below, below, above, valid                        | POST `/api/internal/authorize`, pos, electronics  | DECLINED, `responseCode="51"`, причина `INSUFFICIENT_FUNDS`   |
| **TC-PW-AUTH-24** |   24   | ACTIVE, below, equal, equal, current_month                | POST `/api/internal/authorize`, pos, electronics  | APPROVED, `responseCode="00"`                                 |
| **TC-PW-AUTH-25** |   25   | INACTIVE, below, below, below, current_month              | POST `/api/internal/authorize`, pos, electronics  | DECLINED, причина `CARD_INACTIVE`                             |

> **Примечание.** В кейсах TC-PW-AUTH-09, TC-PW-AUTH-14, TC-PW-AUTH-19 одновременно превышены баланс и лимит. Ожидаемый результат приведён по ТЗ [`tz/04-authorization.md`](tz/04-authorization.md) (лимиты проверяются раньше баланса → код 61).

### 4.2. Тест-кейсы из попарного набора Card-Management (pairwise)

Проецирование 26 сгенерированных строк `cases-card-management.txt` в тест-кейсы Card Management Service.

| ID              | № стр. | Операция / PAN / BIN / Expiry / Status / Amount       | Шаги                                                         | Ожидаемый результат                                                   |
| :-------------- | :----: | :---------------------------------------------------- | :----------------------------------------------------------- | :-------------------------------------------------------------------- |
| **TC-PW-CM-01** |   1    | delete, valid, known, mmyy, INACTIVE, below           | DELETE `/api/cards/{pan}`                                    | 204 No Content, статус DELETED                                        |
| **TC-PW-CM-02** |   2    | read, wrong_length, unknown, empty, ACTIVE, below     | GET `/api/cards/{pan}` (PAN длиной ≠ 16)                     | 400 Bad Request: ошибка валидации длины PAN                           |
| **TC-PW-CM-03** |   3    | reserve, valid, known, mmyy, ACTIVE, equal            | POST `/api/cards/{pan}/reserve`, amount = balance            | 200 OK, `availableBalance` = 0                                        |
| **TC-PW-CM-04** |   4    | create, valid, unknown, iso, ACTIVE, below            | POST `/api/cards`, `bin=999999`, `expiryDate=2029-09`        | 400 Bad Request: ошибка валидации BIN и формата expiryDate            |
| **TC-PW-CM-05** |   5    | create, valid, known, empty, DELETED, below           | POST `/api/cards`, `expiryDate` пустой                       | 400 Bad Request: ошибка валидации формата expiryDate                  |
| **TC-PW-CM-06** |   6    | read, invalid_luhn, known, mmyy, BLOCKED, below       | GET `/api/cards/{pan}` (PAN не проходит Луна)                | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-07** |   7    | delete, invalid_luhn, unknown, iso, ACTIVE, below     | DELETE `/api/cards/{pan}` (PAN не проходит Луна)             | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-08** |   8    | update, wrong_length, known, iso, BLOCKED, above      | PATCH `/api/cards/{pan}` (PAN длиной ≠ 16)                   | 400 Bad Request: ошибка валидации длины PAN                           |
| **TC-PW-CM-09** |   9    | create, valid, unknown, mmyy, INACTIVE, below         | POST `/api/cards`, `bin=999999`                              | 400 Bad Request: ошибка валидации BIN                                 |
| **TC-PW-CM-10** |   10   | update, invalid_luhn, unknown, empty, INACTIVE, equal | PATCH `/api/cards/{pan}` (PAN не проходит Луна)              | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-11** |   11   | read, valid, unknown, iso, DELETED, below             | GET `/api/cards/{pan}` (карта удалена)                       | 404 Not Found: карта не возвращается                                  |
| **TC-PW-CM-12** |   12   | generate, valid, unknown, mmyy, ACTIVE, below         | POST `/api/cards/generate`, `{count: 100, bins: ["400000"]}` | 201 Created: 100 карт, распределение статусов ≈ 95/3/2                |
| **TC-PW-CM-13** |   13   | reserve, valid, known, mmyy, INACTIVE, above          | POST `/api/cards/{pan}/reserve`, amount > balance            | 402 Payment Required: недостаточно средств                            |
| **TC-PW-CM-14** |   14   | generate, valid, unknown, empty, BLOCKED, below       | POST `/api/cards/generate`, `{count: 100}`                   | 201 Created: 100 карт, среди них ≈ 2% BLOCKED                         |
| **TC-PW-CM-15** |   15   | create, valid, known, mmyy, BLOCKED, below            | POST `/api/cards` с валидными параметрами BIN 400000         | 201 Created: PAN 16 цифр, Луна корректен, срок +3 года, статус ACTIVE |
| **TC-PW-CM-16** |   16   | update, invalid_luhn, unknown, mmyy, ACTIVE, above    | PATCH `/api/cards/{pan}` (PAN не проходит Луна)              | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-17** |   17   | generate, valid, known, mmyy, DELETED, below          | POST `/api/cards/generate`, `{count: 100, bins: ["400000"]}` | 201 Created: 100 карт, распределение статусов ≈ 95/3/2                |
| **TC-PW-CM-18** |   18   | generate, valid, unknown, iso, INACTIVE, below        | POST `/api/cards/generate`, `{count: 100}`                   | 201 Created: 100 карт, среди них ≈ 3% INACTIVE                        |
| **TC-PW-CM-19** |   19   | update, wrong_length, unknown, iso, DELETED, equal    | PATCH `/api/cards/{pan}` (PAN длиной ≠ 16)                   | 400 Bad Request: ошибка валидации длины PAN                           |
| **TC-PW-CM-20** |   20   | delete, wrong_length, unknown, empty, DELETED, below  | DELETE `/api/cards/{pan}` (PAN длиной ≠ 16)                  | 400 Bad Request: ошибка валидации длины PAN                           |
| **TC-PW-CM-21** |   21   | reserve, valid, known, mmyy, BLOCKED, below           | POST `/api/cards/{pan}/reserve`, amount ≤ balance            | 200 OK, `availableBalance` уменьшен                                   |
| **TC-PW-CM-22** |   22   | read, wrong_length, known, mmyy, INACTIVE, below      | GET `/api/cards/{pan}` (PAN длиной ≠ 16)                     | 400 Bad Request: ошибка валидации длины PAN                           |
| **TC-PW-CM-23** |   23   | update, invalid_luhn, known, empty, DELETED, above    | PATCH `/api/cards/{pan}` (PAN не проходит Луна)              | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-24** |   24   | update, valid, unknown, mmyy, INACTIVE, below         | PATCH `/api/cards/{pan}` с новым `availableBalance`          | 200 OK: поле обновлено, остальные не изменены                         |
| **TC-PW-CM-25** |   25   | delete, invalid_luhn, unknown, mmyy, BLOCKED, below   | DELETE `/api/cards/{pan}` (PAN не проходит Луна)             | 400 Bad Request: ошибка валидации PAN                                 |
| **TC-PW-CM-26** |   26   | update, valid, unknown, empty, BLOCKED, equal         | PATCH `/api/cards/{pan}` с новым `availableBalance`          | 200 OK: поле обновлено, остальные не изменены                         |
