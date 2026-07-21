# OAK — Server (Spring Boot Backend)

> Project-level context file. Прочитать перед началом любой работы в `oak-server`.

## Что это

`oak-server` — бэкенд самохостящегося клона Discord (кодовое имя проекта **OAK**). Это одно из двух приложений в `IdeaProjects`:

- **`oak-server`** (этот проект) — single-instance сервер на Spring Boot.
- **`oak-client`** — JavaFX desktop-клиент (см. соседнюю директорию, `../oak-client`).

Бэкенд обеспечивает аутентификацию, REST API, real-time сигналинг и чат, а также оркестрирует внешние сервисы (MinIO, Janus). Сам медиа-трафик через сервер не проходит — он только маршрутизирует сигналинг и отдаёт клиентам URL/roomId для подключения к Janus; сама комната в Janus создаётся лениво первым подключившимся клиентом (`exists`-зонд + `create`), а не сервером.

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
| SFU (опционально, оркестрация) | **Janus** | 3+ участника: голос/видео/шаринг экрана. Сервер отдаёт клиенту URL/roomId; клиент сам говорит с Janus по HTTP REST |
| TURN (внешний) | **Coturn** | P2P-fallback для звонков 1-на-1. Лёгкий, самостоятельный |

## Текущее состояние

Бутстрап завершён: `org.example:oak-server`, Java 17, Maven, **Spring Boot 4.1.0**.

- `pom.xml`: spring-boot-starter-web/-security/-data-jpa/-validation, postgresql, jjwt 0.12.6, lombok; test-scope: h2, spring-boot-webmvc-test, spring-boot-starter-test.
- `App.java` — `@SpringBootApplication` + `CommandLineRunner`, проверяющий соединение с БД.
- `entity/User` + `repository/UserRepository` — первая JPA-сущность (`@Table("users")`).
- JPA → PostgreSQL проверено end-to-end (Hibernate создаёт таблицу `users`, проба логирует счётчик строк).
- Security: stateless JWT-авторизация (JJWT HS256, BCrypt). Эндпоинты `/api/auth/{register,login,refresh}`, `/api/users/me`; публично только `/api/auth/**`. Login/refresh выдают пару access-JWT (8 ч) + opaque refresh-токен (30 д, в БД хешем через `entity/RefreshToken` + `RefreshTokenRepository`); `POST /api/auth/refresh` ротирует refresh (старый → `revoked`) и выдаёт новую пару. Refresh-токены объединены в семьи (`family_id`): повторное предъявление уже отозванного токена (reuse = признак кражи) → авто-отзыв всей семьи; истёкший → 401 без затрагивания семьи. Периодическая чистка истёкших `refresh_tokens` — `RefreshTokenCleanup` (`@Scheduled`, `oak.security.refresh-cleanup-interval` = PT6H; чистит по `expiresAt`, чтобы не сломать reuse-detection). Auth-флоу проверен end-to-end (201/409/400/200/401).
- REST каналов/сообщений: `entity/{Channel,ChannelType,Message}` + репозитории, `service/{ChannelService,MessageService}`, `controller/{ChannelController,MessageController}`, DTO. Эндпоинты `GET/POST /api/channels`, `GET/POST /api/channels/{id}/messages`. Запись — REST (вариант A); real-time пуш подключённым клиентам — в шаге WebSocket.
- WebSocket (STOMP): `config/WebSocketConfig` (endpoint `/ws`, broker `/topic`, prefix `/app`), `security/AuthChannelInterceptor` (Bearer-JWT на STOMP CONNECT). `MessageService.create` пушит новое сообщение в `/topic/channel.{id}` — замыкает вариант A: REST-write → WS-delivery. `ChannelService.create` пушит свежесозданный канал в `/topic/channels` (множ. число, чтобы не спутать с per-channel сообщениями) — каждый подключённый клиент (`oak-client`) добавляет его в сайдбар live без переселекта сервера; автор канала игнорирует эхо по id (он уже вставил канал оптимистично). STOMP-heartbeat включён (`HEARTBEAT_MS = 10_000`, simple-broker + inline `ThreadPoolTaskScheduler`) — сервер закрывает зависшие сессии, и `PresenceTracker` оперативно публикует OFFLINE.
- Presence: `websocket/PresenceTracker` — in-memory реестр STOMP-сессий на `SessionConnectEvent`/`SessionDisconnectEvent` (идемпотентный по `sessionId` против дублей disconnect; пуш дельт `{username, ONLINE|OFFLINE}` в `/topic/presence` на переходах 0↔1). `controller/PresenceController` (`GET /api/presence` — снимок онлайн), `GET /api/users` — ростер `UserSummary`. Без правок `WebSocketConfig`/`SecurityConfig` (`/topic/presence` уже покрыт simple-broker, эндпоинты авто-защищены).
- WebRTC-сигналинг (звонки 1-на-1): `websocket/CallSignalingHandler` (`@Controller`, `@MessageMapping("/app/call.signal")` → адресный relay `convertAndSendToUser(to, "/queue/call.signal")`; `from` штампуется сервером из Principal), брокер += `/queue` (`WebSocketConfig`). `controller/CallController` (`GET /api/call/config` → ICE/TURN из `oak.turn.*`), DTO `CallSignal`/`CallConfig`/`IceCandidate`, `config/TurnProperties`. Сервер медиа не терминирует — только релеит сигналинг. Звонки 1-на-1 работают end-to-end (клиент `oak-client`).
- Voice-membership (кто в какой голосовой комнате): `websocket/VoiceMembershipTracker` — калька `PresenceTracker` (in-memory, `@EventListener SessionDisconnectEvent`, идемпотентность по `sessionId`, пуш дельт вне лока), но состояние двумерное — `session→username`, `session→channelId`, `channelId→(username→refcount)`; пуш `{username, channelId, JOINED|LEFT}` в `/topic/voice` только на 0↔1 по (channel,user). `websocket/VoiceMembershipHandler` (`@Controller`, `@MessageMapping("/app/voice.join"|"/voice.leave")` с `@Header("simpSessionId")`) — join/leave через STOMP-SEND, чтобы disconnect сам убирал membership (как presence OFFLINE), без best-effort REST-leave перед `System.exit`. `controller/VoiceMembershipController` (`GET /api/voice/members` — снимок `{channelId, usernames[]}`). Без правок `WebSocketConfig`/`SecurityConfig` (брокер покрывает `/topic/voice`, `/app` уже зарегистрирован).
- MinIO (вложения): `config/{MinioProperties,MinioConfig}` (один `MinioClient` на `publicEndpoint`), `service/{AttachmentUrlSigner,MinioAttachmentService,NoopAttachmentUrlSigner}` (presigned PUT/GET, `newKey`, bucket-create в `@PostConstruct`), `controller/AttachmentController` (`POST /api/attachments` → ключ + presigned PUT). `Message` += `attachmentKey`/`attachmentFileName`; presigned GET перевыписывается в `MessageService.toResponse`. В test-профиле MinIO выключен (`oak.minio.enabled=false`), стоит noop-подписчик.
- **Аватары пользователей:** `User` += `avatarKey` (калька `Message.attachmentKey`, `@Column(length=512)`); `AttachmentUrlSigner.newAvatarKey` (префикс `avatars/`, реализация в `MinioAttachmentService`/`NoopAttachmentUrlSigner`). `UserResponse`/`UserSummary` += `avatarUrl` (presigned GET, перевыписывается как `attachmentUrl` — `from(User, AttachmentUrlSigner)`). `UserController` += 3 эндпоинта `/api/users/me/avatar` (POST slot + POST `/confirm` + DELETE) — двухшаговый upload (mint slot → клиент PUT в MinIO → `/confirm` привязывает key к user, чтобы упавший upload не оставил dangling key); MIME-валидация по расширению (только png/jpg/jpeg, иначе 400) — единственный server-side type gate. `AuthController.register` остаётся на `from(User)` (новый юзер → avatarUrl null). Тест `UserAvatarIntegrationTest` (MockMvc, noop-сигнер → avatarUrl null, проверяет key-персист через репо).
- Тесты: `AuthFlowIntegrationTest` (вкл. refresh/ротацию/переиспользование), `ChannelMessageFlowIntegrationTest`, `UsersPresenceIntegrationTest`, `AttachmentIntegrationTest` (MockMvc), `UserAvatarIntegrationTest` (MockMvc, two-phase avatar upload), `AuthChannelInterceptorTest`, `MessageServiceTest` (Mockito, пуш + валидация content-или-attachment), `PresenceTrackerTest`, `VoiceMembershipTrackerTest`, `JwtTokenProviderTest`, `AppTest`. `mvn test` — 47 тестов, всё зелёное.
- Периферия (`env/docker-compose.yml`): postgres, minio, janus (`canyan/janus-gateway`), coturn — поднимаются и проверены.

- **`TURN_EXTERNAL_IP` в `env/.env` должен быть LAN-IP машины** (например `192.168.0.78`), не `127.0.0.1` и не публичный IP. Coturn в Docker рекламирует relay-кандидаты с этим IP; клиент (на хосте) должен до них достучаться. Изначально стоял `127.0.0.1` — не работал для TURN-relay.

Auth-hardening завершён: refresh-токены объединены в семьи с reuse-detection (переиспользование отозванного токена → авто-отзыв всей семьи) и периодической чисткой истёкших токенов (`RefreshTokenCleanup`). WebRTC-звонки 1-на-1 (сигналинг-relay) и Janus (SFU-комнаты) реализованы end-to-end. Из открытого остался только server-side logout (см. последний пункт чек-листа ниже); остальное закрыто.

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
   `JavaFX: GET /api/channels/{id}/room → Spring Boot отдаёт {janusRestUrl, roomId} → JavaFX сам создаёт комнату в Janus (exists-зонд + create) и говорит с Janus по REST long-poll`.
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
- [x] Интеграция с MinIO: presigned-URL (`MinioAttachmentService`), `POST /api/attachments`, bucket-create при старте, `Message` + вложения.
- [x] Интеграция с Janus: `GET /api/channels/{id}/room` → `{janusUrl, roomId}` (JanusProperties). Клиент говорит с Janus по REST (WS не работает на Docker Desktop/Windows). Smoke ✓.
- [x] WebRTC сигналинг-relay (1-на-1): `CallSignalingHandler` (`@MessageMapping("/app/call.signal")` → адресный relay в `/user/queue/call.signal`, `from` штампуется сервером из Principal); брокер += `/queue` (`WebSocketConfig`); `CallController` (`GET /api/call/config` → ICE/TURN из `oak.turn.*`). Реализовано end-to-end.
- [x] Refresh-token reuse-detection: семья (`familyId`) + авто-отзыв всей семьи при переиспользовании отозванного refresh-токена (`AuthService.refresh` + `@Transactional(noRollbackFor = ResponseStatusException.class)`, чтобы family-revoke не откатился вместе с 401).
- [x] Чистка истёкших `refresh_tokens`: `RefreshTokenCleanup` (`@Scheduled`, `oak.security.refresh-cleanup-interval` = PT6H), `deleteByExpiresAtBefore`; `@EnableScheduling` на `App`.
- [ ] **Server-side logout**: `POST /api/auth/logout` — отзыв предъявленного refresh-токена текущей сессии (`AuthService.logout` → `stored.setRevoked(true)`, идемпотентный, `204 No Content` всегда — не раскрывать, существовал ли токен). Сейчас выхода на стороне сервера нет: клиент (`oak-client`) выбрасывает токен локально, а строка в БД живёт до истечения срока. Опционально: `logout-all` (отзыв всей семьи = «выйти со всех устройств», переиспользует family-механику reuse-detection) и вызов эндпоинта из `oak-client` по кнопке выхода.

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
- **Presigned-URL печатаются на `publicEndpoint`**, не на внутреннем `endpoint` — иначе клиент не дойдёт до MinIO. MinIO-SDK: класс `io.minio.GetPresignedObjectUrlArgs`, метод `getPresignedObjectUrl` (возвращает `String`); класса `GetPresignedUrlArgs` **нет** (частая опечатка). В test-профиле MinIO выключен (`oak.minio.enabled=false`), и `NoopAttachmentUrlSigner` (`@ConditionalOnProperty havingValue=false`) подменяет подписчика — иначе контекст без MinIO не поднялся бы.
- **Истёкший access-JWT на STOMP `CONNECT` — теперь обрабатывается клиентом.** `JwtTokenProvider.isValid` возвращает false для истёкших (8 ч, `oak.security.jwt.access-token-expiration`), и `AuthChannelInterceptor` отбрасывает `CONNECT`. Клиент (`oak-client`) при REST 401 silent-refresh'ит access через `POST /api/auth/refresh` (refresh-токен, ротация) и поднимает WS уже со свежим токеном — симптом «Cannot load channels»/STOMP auth-fail более не требует ручного ре-логина (см. `../oak-client/CLAUDE.md`). На сервере без изменений: поведение 401 корректно.
- **`@MessageMapping` сканируется только на `@Controller`-бинах.** `SimpAnnotationMethodMessageHandler.isHandler` проверяет именно `@Controller`; бин с `@Component`/`@Service` метод не найдёт — в логе будет «No matching message handler methods» при SEND на `/app/...`. Ставьте `@Controller` на обработчик сигналинга (`CallSignalingHandler`).
- **Reuse-detection + `@Transactional` rollback.** При переиспользовании отозванного refresh-токена family-revoke (отзыв всей семьи) делается **до** бросания 401. По умолчанию `ResponseStatusException` (это `RuntimeException`) помечает tx rollback-only → family-revoke бы откатился, а в тесте со следующими шагами вылетел бы `UnexpectedRollbackException`. Поэтому `AuthService.refresh` несёт `@Transactional(noRollbackFor = ResponseStatusException.class)` — revoke коммитится, а 401 всё равно возвращается через resolver (от исхода tx не зависит). Не «чините» это убиранием `noRollbackFor`.
- **Чистка refresh-токенов — по `expiresAt`, а не по факту `revoked`.** Отозванный-но-не-истёкший токен нужно хранить до естественного истечения: иначе повторное предъявление украденной копии не найдёт его по хешу, и reuse-detection не сработает. Поэтому `deleteByExpiresAtBefore` (а не `deleteByRevokedTrue`). Рост таблицы всё равно ограничен сроком жизни refresh (30 дней).
- **`ddl-auto: update` НЕ удаляет колонки.** Hibernate при `update` только добавляет новые таблицы/колонки под изменившуюся сущность — он никогда не дропает то, что пропало из модели. Поэтому, когда поле `email` убрали из `User`, в существующей dev-БД колонка `users.email` (с её UNIQUE-констрейнтом) осталась висеть «мёртвой»: приложение её просто не использует, но и не чистит. В тест-профиле это незаметно (`create-drop` — схема пересоздаётся из сущности каждый прогон). Чтобы убрать колонку в реальной БД — ручной `ALTER TABLE users DROP COLUMN email, DROP CONSTRAINT <uniq_email_constraint>`; либо завести миграционный инструмент (Flyway/Liquibase) до перехода на `ddl-auto: none` в проде.
- **Аватар: сервер байты не терминирует → MIME-проверка только по расширению имени.** `POST /api/users/me/avatar` mint'ит presigned-slot, клиент сам PUT'ит байты в MinIO — сервер не знает ни тип, ни размер файла. Поэтому тип контролируется расширением `fileName` (png/jpg/jpeg → иначе 400), а размер — на клиенте (gate до crop, 16 MB). Не пытаться добавить multipart-приём/MIME-sniff на сервере — это ломает принцип «presigned-URL, без BLOB в PostgreSQL, без терминирования байтов». Upload двухшаговый (slot → PUT → `/confirm`) специально, чтобы привязать `avatarKey` к `User` только после успешного PUT — упавший/отменённый upload не оставляет dangling key.

## Ссылки

- Блюпринт проекта (стек + архитектура): `../oak.txt`
- Сопряжённый клиент: `../oak-client` (см. его `CLAUDE.md`)
