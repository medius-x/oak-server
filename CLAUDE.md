# OAK — Server (Spring Boot Backend)

> Project-level context file. Прочитать перед началом любой работы в `oak-server`.

## Что это

`oak-server` — бэкенд самохостящегося клона Discord (кодовое имя проекта **OAK**). Это одно из двух приложений в `IdeaProjects`:

- **`oak-server`** (этот проект) — single-instance сервер на Spring Boot.
- **`oak-client`** — JavaFX desktop-клиент (см. соседнюю директорию, `../oak-client`).

Бэкенд обеспечивает аутентификацию, REST API, real-time сигналинг и чат, а также оркестрирует внешние сервисы (MinIO, Janus). Сам медиа-трафик через сервер не проходит — он только маршрутизирует сигналинг и создаёт комнаты.

## Стек технологий (целевой)

| Слой | Технология | Назначение |
|------|-----------|------------|
| Core framework | **Spring Boot** | Основа приложения, single-instance |
| REST API | **Spring Web** | Регистрация, логин, списки каналов |
| Real-time сигналинг и чат | **Spring WebSocket** | WSS-соединения, обрабатываются in-memory (стандартные Java-коллекции для connected users) |
| Безопасность | **Spring Security + JWT** | Защита и REST, и WebSocket endpoint'ов |
| ORM | **Spring Data JPA / Hibernate** | Доступ к PostgreSQL |
| БД | **PostgreSQL** | Users, Channels, текстовые сообщения |
| Object storage | **MinIO** | Медиа, изображения, вложения (клиент грузит напрямую по presigned URL) |
| SFU (опционально, оркестрация) | **Janus** | 3+ участника: голос/видео/шаринг экрана. Backend общается с Janus по HTTP REST |
| TURN (внешний) | **Coturn** | P2P-fallback для звонков 1-на-1. Лёгкий, самостоятельный |

## Текущее состояние

Бутстрап завершён: `org.example:oak-server`, Java 17, Maven, **Spring Boot 4.1.0**.

- `pom.xml`: spring-boot-starter-web/-security/-data-jpa/-validation, postgresql, jjwt 0.12.6, lombok; test-scope: h2, spring-boot-webmvc-test, spring-boot-starter-test.
- `App.java` — `@SpringBootApplication` + `CommandLineRunner`, проверяющий соединение с БД.
- `entity/User` + `repository/UserRepository` — первая JPA-сущность (`@Table("users")`).
- JPA → PostgreSQL проверено end-to-end (Hibernate создаёт таблицу `users`, проба логирует счётчик строк).
- Security: stateless JWT-авторизация (JJWT HS256, BCrypt). Эндпоинты `/api/auth/register`, `/api/auth/login`, `/api/users/me`; публично только `/api/auth/**`. Auth-флоу проверен end-to-end (201/409/400/200/401).
- REST каналов/сообщений: `entity/{Channel,ChannelType,Message}` + репозитории, `service/{ChannelService,MessageService}`, `controller/{ChannelController,MessageController}`, DTO. Эндпоинты `GET/POST /api/channels`, `GET/POST /api/channels/{id}/messages`. Запись — REST (вариант A); real-time пуш подключённым клиентам — в шаге WebSocket.
- WebSocket (STOMP): `config/WebSocketConfig` (endpoint `/ws`, broker `/topic`, prefix `/app`), `security/AuthChannelInterceptor` (Bearer-JWT на STOMP CONNECT). `MessageService.create` пушит новое сообщение в `/topic/channel.{id}` — замыкает вариант A: REST-write → WS-delivery. STOMP-heartbeat включён (`HEARTBEAT_MS = 10_000`, simple-broker + inline `ThreadPoolTaskScheduler`) — сервер закрывает зависшие сессии, и `PresenceTracker` оперативно публикует OFFLINE.
- Presence: `websocket/PresenceTracker` — in-memory реестр STOMP-сессий на `SessionConnectEvent`/`SessionDisconnectEvent` (идемпотентный по `sessionId` против дублей disconnect; пуш дельт `{username, ONLINE|OFFLINE}` в `/topic/presence` на переходах 0↔1). `controller/PresenceController` (`GET /api/presence` — снимок онлайн), `GET /api/users` — ростер `UserSummary` (без email). Без правок `WebSocketConfig`/`SecurityConfig` (`/topic/presence` уже покрыт simple-broker, эндпоинты авто-защищены).
- Тесты: `AuthFlowIntegrationTest`, `ChannelMessageFlowIntegrationTest`, `UsersPresenceIntegrationTest` (MockMvc), `AuthChannelInterceptorTest`, `MessageServiceTest` (Mockito, пуш), `PresenceTrackerTest` (Mockito, идемпотентность дубля disconnect), `JwtTokenProviderTest`, `AppTest`. `mvn test` — 24 теста, всё зелёное.
- Периферия (`env/docker-compose.yml`): postgres, minio, janus (`canyan/janus-gateway`), coturn — поднимаются и проверены.

На очереди: интеграции MinIO (presigned-URL) и Janus (SFU-комнаты).

## Архитектура и потоки связи (роль сервера)

1. **Auth & REST** — `JavaFX → Spring Boot REST (HTTPS/JWT) → PostgreSQL`.
   Сервер валидирует учётные данные, выдаёт JWT, отдаёт списки каналов.

2. **Загрузка файлов** — сервер выступает брокером presigned-URL:
   `JavaFX просит URL → Spring Boot генерирует presigned через MinIO → отдаёт клиенту`.
   Сам файл через сервер **не** идёт — клиент грузит прямо в MinIO.

3. **Real-time текст и signaling 1-на-1** — `JavaFX ↔ Spring Boot WebSocket (WSS/JWT)`.
   Состояние подключённых пользователей держится **in-memory** (стандартные коллекции).
   Через WebSocket маршрутизируются сообщения чата и SDP/ICE-кандидаты для P2P-звонков.

4. **Многопользовательские голос/видео (3+) и шаринг экрана** —
   `JavaFX просит создать комнату → Spring Boot создаёт её в Janus через HTTP REST → возвращает клиенту данные для прямого подключения к Janus по WebSocket`.
   SRTP-медиа течёт между клиентом и Janus, сервер его не видит.

5. **Прямые звонки 1-на-1** — сервер только ретранслирует сигналинг (см. п.3).
   Медиа P2P; при недоступности через NAT — через внешний **Coturn** (TURN-сервер не управляется бэкендом).

## Предполагаемая предметная модель (PostgreSQL)

Один self-hosted инстанс OAK **и есть** сервер — отдельной сущности «гильдия/сервер» в БД нет.
Пользователи и каналы принадлежат самому инстансу напрямую. Инстанс **single-tenant** и ничего
не знает о других инстансах OAK: «список серверов», который видит клиент, формируется на стороне
клиента (он подключается к нескольким физически разным инстансам, см. `../oak-client/CLAUDE.md`).
Сущности (Hibernate/JPA):

- **User** — учётная запись, учётные данные, профиль.
- **Channel** — канал верхнего уровня (текстовый / голосовой), принадлежит инстансу.
- **Message** — текстовое сообщение (с ссылкой на вложение в MinIO, если есть).

> Структуру таблиц уточнять по мере реализации; выше — доменная область.

## Предлагаемая структура пакетов (roadmap)

Текущий корень: `oak.server`. По мере реализации:

```
oak.server
├── App.java                 // @SpringBootApplication, точка входа
├── config/                  // Security (JWT filter), WebSocket config, CORS
├── controller/              // REST-контроллеры (auth, channels, messages)
├── websocket/               // обработчики Spring WebSocket, in-memory user registry
├── service/                 // бизнес-логика + интеграции (MinIO, Janus)
├── repository/              // Spring Data JPA репозитории
├── entity/                  // JPA-сущности (User, Channel, Message)
├── security/                // JWT-провайдер, фильтры, UserDetails
└── dto/                     // запросы/ответы API
```

## Что предстоит реализовать

- [x] Подключить Spring Boot (starter-web, -security, -data-jpa, -validation) + postgres-драйвер.
- [x] Бутстрап `@SpringBootApplication` (замена `App.main`).
- [x] Слой security: регистрация/логин, выдача и валидация JWT (`JwtTokenProvider`, `JwtAuthenticationFilter`, `JpaUserDetailsService`, `SecurityConfig`; эндпоинты `/api/auth/{register,login}`, `/api/users/me`). Проверено end-to-end.
- [x] JPA: `User` + `UserRepository` (Channel/Message — по мере реализации).
- [x] REST API: каналы/сообщения (`Channel`/`Message` + эндпоинты `/api/channels`, `/api/channels/{id}/messages`; real-time пуш — в WebSocket-шаге).
- [x] WebSocket (STOMP): JWT на CONNECT, пуш новых сообщений в `/topic/channel.{id}`.
- [x] Presence: in-memory реестр подключённых (`PresenceTracker`), пуш дельт в `/topic/presence`, `GET /api/presence` + `GET /api/users`.
- [x] STOMP heartbeat (обе стороны): server `setHeartbeatValue + TaskScheduler` (`WebSocketConfig`), клиент — согласование STOMP 1.2 + исходящие `\n` + read-watchdog (`StompClient`). Оперативный offline-детект при аварийных обрывах.
- [ ] Интеграция с MinIO: генерация presigned-URL.
- [ ] Интеграция с Janus: создание/управление SFU-комнатами через HTTP REST.

## Конвенции разработки

- **Java 17**, сборка Maven (`mvn clean package`, `mvn spring-boot:run`).
- Пакеты: `oak.server.*`. Соответствие директорий и пакетов — обязательно.
- **Lombok** убирает бойлерплейт: `@RequiredArgsConstructor` (DI), `@Getter/@Setter/@NoArgsConstructor(access = PROTECTED)` на JPA-сущностях, `@Slf4j` (логирование). На сущностях **без** `@Data`/`@ToString`/`@EqualsAndHashCode` (ленивые ассоциации, нестабильный hashcode). IDE: нужен Lombok-плагин + включённая annotation processing.
- **Тесты**: интеграционные через `@SpringBootTest + MockMvc` на профиле `test` (in-memory H2, без Docker/Postgres); `mvn test` самодостаточен и изолирован.
- Real-time состояние подключённых пользователей — **только in-memory** (коллекции), не в БД.
- Медиа-файлы — **только** presigned-URL в MinIO, не хранить BLOB'ы в PostgreSQL.
- Сервер **не** терминирует медиа-трафик; только сигналинг + оркестрация Janus/MinIO.
- Секреты (JWT-секрет, креды БД/MinIO/Janus) — через конфиг/переменные окружения, не в коде.
- All endpoints (REST и WebSocket) защищены Spring Security + JWT.

## Грабли и интеграционный контракт

Уроки из пойманных багов (не воспроизводить):

- **`/ws` должен быть `permitAll` в `SecurityConfig`.** Рукопожатие WebSocket — это HTTP-запрос, и оно проходит через Spring Security; JWT при этом ещё нет (он в STOMP-фрейме `CONNECT`, а не в заголовках рукопожатия). Поэтому эндпоинт `/ws` разрешаем явно, а аутентификацию делаем на STOMP-уровне в `AuthChannelInterceptor` (читает JWT из заголовка `CONNECT`). Не «закрывайте» `/ws` требованием аутентификации — рукопожатие упадёт в 401, и WS вообще не поднимется.
- **STOMP native-заголовки чувствительны к регистру** (Spring сопоставляет по точному ключу, а не по case-insensitive-спеке). `AuthChannelInterceptor` поэтому проверяет и `Authorization`, и `authorization`. Клиент (`oak-client` `StompClient`) шлёт `Authorization` с большой буквы — держите регистр согласованно между клиентом и сервером.
- **`Page.getContent()` неизменяем.** `Collections.reverse(page.getContent())` → `UnsupportedOperationException` при ≥2 элементах (при 1 — no-op, поэтому тест с одним сообщением баг пропускает). Копируйте: `new ArrayList<>(...)`. «Последние N по возрастанию» = `DESC + LIMIT` + reverse (ASC + LIMIT дало бы старейшие N, а не новейшие).
- WS-рукопожатие и cross-client доставка **не покрываются `MockMvc`-тестами** (это интеграционный уровень). В dev включён DEBUG-лог `org.springframework.web.socket` / `org.springframework.messaging.simp` для видимости.
- **`SessionDisconnectEvent` может прийти >1 раза за сессию** (Spring так и делает — компоненты должны быть идемпотентны к повторным disconnect). Поэтому в `PresenceTracker` удаление ключится по `sessionId` (`sessionToUsername.remove(session)` возвращает null на дубликате → no-op), а не по голому счётчику username — иначе дубль даёт under-count/уход в минус и ложный `OFFLINE`. Граничные решения (0↔1) сериализованы одним локом — это заодно исключает гонку порядка online/offline при одновременном реконнекте одного юзера. Principal на сессии: `StompHeaderAccessor.wrap(event.getMessage()).getUser().getName()` (его кладёт `AuthChannelInterceptor` в `preSend` ещё до публикации события).
- **`setHeartbeatValue` требует `TaskScheduler`** — без него Spring падает с `IllegalArgumentException` при старте брокера. В `WebSocketConfig` планировщик создаётся inline (`ThreadPoolTaskScheduler`, daemon, `initialize()`) и передаётся в `enableSimpleBroker(...).setTaskScheduler(...)`. Heartbeat согласуется с клиентом по STOMP 1.2 (клиент шлёт `heart-beat:10000,10000` в CONNECT); при пропадании трафика в пределах интервала сервер закрывает сессию → `SessionDisconnectEvent` → `PresenceTracker` OFFLINE.

## Ссылки

- Блюпринт проекта (стек + архитектура): `../oak.txt`
- Сопряжённый клиент: `../oak-client` (см. его `CLAUDE.md`)
