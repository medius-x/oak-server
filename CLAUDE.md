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
- MinIO (вложения): `config/{MinioProperties,MinioConfig}` (один `MinioClient` на `publicEndpoint`), `service/{AttachmentUrlSigner,MinioAttachmentService,NoopAttachmentUrlSigner}` (presigned PUT/GET, `newKey`, bucket-create в `@PostConstruct`), `controller/AttachmentController` (`POST /api/attachments` → ключ + presigned PUT; **`GET /api/attachments/link?key=…`** → свежий presigned GET для ключа, который клиент уже держит — переподписка для стримового воспроизведения, пережившего свой часовой URL (пауза на часы → resume); key в QUERY-параметре (в ключе слэши `attachments/<uuid>/<name>` — path-вариант нежизнеспособен), unknown key → 404 через `MessageService.attachmentExists` (native-LIKE по JSON-колонке `messages.attachments` — ключи содержат uuid, ложных совпадений между ключами не бывает; модель доступа: ключ = capability, права канала не пересчитываются — зеркально тому, как presigned уже раздаются в MessageResponse)). `Message` += `attachmentKey`/`attachmentFileName`; presigned GET перевыписывается в `MessageService.toResponse`. В test-профиле MinIO выключен (`oak.minio.enabled=false`), стоит noop-подписчик. **Парный деплой** с клиентским авторекавери (`RestClient.freshAttachmentLink`).
- **RBAC (роли + разрешения, итерация 1 — серверный фундамент):** Discord-подобная модель поверх single-tenant-инстанса (гильдии нет → роли **глобальные per-instance**, `User ↔ Role` многие-ко-многим через `user_roles`). `entity/Permission` — полный каталог (~28 прав в группах General/Text/Voice), каждое со стабильным битом `1L << ordinal()`; маска хранится как `long permissions` на `Role` (и `allow`/`deny` на `ChannelRoleOverride`). `entity/Role` (name, color, position, permissions, mentionable, hoist, `RoleKind` EVERYONE/OWNER/CUSTOM); `entity/ChannelRoleOverride` (channel, role, allow, deny, unique channel+role). `User` += `@ManyToMany roles` + `boolean banned`. **Сидер** `service/RoleSeeder` (`ApplicationRunner`, @Order(0), идемпотентный): сеет `@everyone` (position 0, дефолтные права send/read/attach/connect/speak) и `OWNER` (position `MAX_VALUE/2`, `ADMINISTRATOR`, protected); **бутстрап владельца** — первый зарегистрированный юзер получает OWNER в `AuthService.register` (`count()==0`), а fallback для существующей dev-БД без владельца — в сидере промоутится юзер с минимальным id. `service/AuthorizationService` — резолвинг: `effectiveBase` = OR(@everyone + роли юзера), OWNER/`ADMINISTRATOR` → все права; `hasInChannel` — Discord-style (deny оверрайда бьёт allow, иначе база; ADMINISTRATOR обходит оверрайды); иерархия `canActOn` (строго выше top-position, OWNER-цель иммунна), `canAssignRole`. Чеки — **явные** через `requireServer`/`requireInChannel`/`requireCanActOn` (кидают `ResponseStatusException(FORBIDDEN)`), **не** `@PreAuthorize` (method-security не включён, авторитеты Spring не тронуты — `ROLE_USER`; права резолвятся из БД по username → смена роли сразу生效 без перевыпуска JWT). REST: `GET /api/permissions` (каталог name+group), `GET/POST /api/roles`, `PATCH/DELETE /api/roles/{id}`, `PUT /api/roles/order` (MANAGE_ROLES+hierarchy; built-ins protected), `GET /api/members` + `PUT /api/members/{id}/roles` + `POST .../kick` + `POST/DELETE .../ban` (MANAGE_MEMBERS/KICK/BAN + иерархия), `GET /api/users/me/permissions` (для клиентского гейта), `GET /api/members/bans`. Enforced: `ChannelService.create` (MANAGE_CHANNELS), `MessageService.create` (SEND_MESSAGES + channel-override), `MessageService.listByChannel` (READ_MESSAGE_HISTORY). Ban: `User.banned` enforced на **каждом** REST-запросе (`JwtAuthenticationFilter` → `PrincipalUser.isBanned()` → `sendError(401)` + short-circuit без `filterChain.doFilter`; пиггибэк на уже существующий lookup фильтра, 0 доп. запросов), в `AuthService.login` (403), в `AuthService.refresh` (отзыв всей refresh-семьи + 401) и в `AuthChannelInterceptor` на STOMP CONNECT — бан срабатывает **немедленно** (access-JWT живёт 8 ч), а не только при следующем логине. Push: `/topic/roles` (`RoleEvent`), `/topic/members` (`MemberEvent`); кик/бан — кооперативный push `/user/queue/member.kick` (клиент отключится). Контроллеры/сервисы/DTO (`RoleResponse` с `permissions` как `Set<String>` имён, `MemberResponse`, `MyPermissions`, …) — в `oak.server.{controller,service,dto}`. **Итерация 2 (сервер завершён):** channel-overrides CRUD (`ChannelOverrideController` `/api/channels/{id}/overrides/{roleId}` upsert/list/delete, MANAGE_CHANNELS; allow/deny как `Set<String>` ↔ bitmask); enforcement `ATTACH_FILES` (в `MessageService.create`, когда есть attachmentKey, channel-scoped) + `CONNECT` (`RoomController.room` REST + `VoiceMembershipHandler.join` STOMP, channel-scoped). **Голосовой mute/deafen/kick end-to-end — готов (итерация 4, см. ниже).** (калька `Message.attachmentKey`, `@Column(length=512)`); `AttachmentUrlSigner.newAvatarKey` (префикс `avatars/`) + `newOriginalKey` (`avatars/originals/`) для pre-crop оригинала. `User` += также `avatarOriginalKey` (оригинал для перекроя). `UserResponse`/`UserSummary` += `avatarKey` (стабильный — клиентский disk-кэш-ключ) + `avatarUrl` (presigned GET, перевыписывается как `attachmentUrl` — `from(User, AttachmentUrlSigner)`); **`avatarOriginalKey` НЕ входит в DTO** (lazy). `UserController` эндпоинты `/api/users/me/avatar`: POST (avatar-slot) + POST `/original` (original-slot) + POST `/confirm` (`{avatarKey, originalKey}`, originalKey nullable = re-crop) + DELETE (обнулить оба) + **GET `/original`** (lazy presigned оригинала, 404 если нет). MIME-валидация по расширению (только png/jpg/jpeg, иначе 400) — единственный server-side type gate. `AuthController.register` остаётся на `from(User)` (новый юзер → avatarUrl null). При replace/remove старый объект удаляется из MinIO (`AttachmentUrlSigner.delete` → `removeObject`; noop в test). `confirm`/`remove` пушат `/topic/users` (`SimpMessagingTemplate`, `UserSummary.from(user,signer)`) — live-обновление аватара у других подключённых клиентов. Тест `UserAvatarIntegrationTest` (MockMvc, noop-сигнер → avatarUrl null, проверяет key-персист через репо).
- **Упоминания + read-state (непрочитанное):** `service/MentionParser` парсит plain-`@token` на read-time в `MessageService.toResponse` (единственная точка для create-broadcast и history-fetch; БЕЗ БД-колонки). `MessageResponse` += `List<MentionResponse> mentions`. `entity/ChannelReadState(user,channel,lastReadMessageId)` + `service/ReadStateService.ack`/`snapshot` + `controller/ReadStateController` (`GET /api/read-states`, `POST /api/channels/{id}/read`, body optional). Bootstrap в `AuthService.register` → `seedInitialReadStates` (новичок не видит архив как unread). `RoleService` валидирует имя роли `[A-Za-z0-9_-]{1,32}` (чтобы plain-`@token` grammar не ломалась на пробелах). Подробно — roadmap-пункт ниже.
- **Редактирование + удаление сообщений (Discord-faithful):** `PATCH`/`DELETE /api/channels/{channelId}/messages/{messageId}` (`MessageController` → `MessageService.edit`/`delete`). Гейт `requireCanModify`: своё — всегда; чужое в серверном канале — `MANAGE_MESSAGES` (канальный, override-aware, OWNER/ADMIN bypass); **в DM/group-DM — только автор**: DM-ветка `hasInChannel` возвращает participant-check для ЛЮБОГО права (стоит до ADMINISTRATOR-short-circuit), поэтому модераторское право в DM не спрашивают вовсе — иначе любой участник переписывал бы чужие ЛС. Участие отдельно проверяется `requireInChannel(READ_MESSAGE_HISTORY)` (это в DM = participant-чек). Edit: `EditMessageRequest(@NotBlank @Size(max=4000) content)` — вложение не редактируется (attachment-only сообщение просто получает текст), blank → 400 bean-validation; `Message.editedAt` (nullable `edited_at`, **ручная установка** — НЕ `@UpdateTimestamp`) штампуется в edit; ответ и broadcast идут через `toResponse` (mentions re-parse, presigned re-sign). Delete: без tombstone, строка исчезает у всех (как Discord). Broadcast: «один топик = один тип» — edit → `/topic/channel.{id}.edited` (полный `MessageResponse`), delete → `/topic/channel.{id}.deleted` (bare `Long`); **`DmSubscriptionInterceptor` regex расширен** на оба суффикса (см. грабли). `MessageRepository.findByIdAndChannelId` — 404 на сообщение чужого канала (нет cross-channel утечки). Известные ограничения v1 (осознанные): unread/mention-счётчики НЕ декрементируются при delete; mention-снапшот не пересчитывается на edit. Тесты: `MessageEditDeleteIntegrationTest` (гейты каналов + DM-«только автор» + валидация/404) + `MessageServiceTest` (broadcast-верификации .edited/.deleted).
- **Emoji-реакции на сообщения (Discord-style):** `PUT`/`DELETE `/api/channels/{cid}/messages/{mid}/reactions/{slug}` (`MessageController` → `MessageService.addReaction`/`removeReaction`) — оба идемпотентны, оба отвечают **200 с полным снапшотом `List<ReactionSummary>`** (клиент применяет REST-ответ, WS-эхо идемпотентно поверх). Сущность `MessageReaction` (unique `message_id`+`user_id`+`reaction`; суррогатный id, идиома ChannelParticipant); в колонке — **slug** из whitelist `MessageService.REACTION_SLUGS` (10 по референсу юзера: 💯 👍 👎 ❤ 👉 👀 😢 😄 ✅ ❌; НЕ глифы — ASCII-безопасность в path/БД), чужой slug → 400. `ReactionSummary` несёт **параллельные** `userIds`/`usernames` (id[i]↔ник[i], вставочный порядок) — «моя ли» решает клиент по selfId, usernames кормят попап «кто поставил»; репозиторийные `findByMessageId`/`findByMessageIdIn` — `@EntityGraph("user")` (иначе чтение username ленивого user-прокси = SELECT на каждую реакцию). Broadcast: `/topic/channel.{id}.reactions` (`ReactionEvent{messageId, List<ReactionSummary>}`; `ReactionSummary{reaction, count, userIds}` — «моя ли» решает клиент по selfId, payload broadcast). Снапшот детерминирован (сортировка по slug, userIds в порядке вставки) — REST-ответ и WS-эхо рендерятся одинаково. Гейт: `requireInChannel(ADD_REACTIONS)` — право УЖЕ было в enum (Group.TEXT); в DM та же проверка вырождается в participant-check (ветка `hasInChannel` до ADMINISTRATOR). `@everyone`-дефолт: `RoleSeeder.EVERYONE_DEFAULTS` += ADD_REACTIONS — **идемпотентный seeder НЕ обновит существующий dev-каталог** (разово: чекбокс в Server Settings или `UPDATE roles SET permissions = permissions | 262144 WHERE kind='EVERYONE'`); **СМОК-ФИКС 2026-09-29: живая dev-БД была засеяна ДО бита — плебей (admin23) терял реакции ЦЕЛИКОМ (писать мог: SEND_MESSAGES в дефолтах с RBAC-итерации 1; OWNER не замечал через ADMINISTRATOR). Закрыто `ensureEveryoneAddReactions()` — безусловный ре-грант при каждом старте (калька ensureEveryoneChangeNickname; заменил мануальную SQL-заметку). Регрессионный тест `everyoneAddReactionsSelfRepairsForDatabasesSeededBeforeTheBit` (снять бит → 403 → repair → 200)**. **N+1-инвариант: `toResponse(message, reactions)` принимает реакции ПАРАМЕТРОМ и сам в репозиторий не ходит** — `listByChannel` батчит один `findByMessageIdIn` на страницу; create → `List.of()`; edit → точечный (`.edited`-push ОБЯЗАН нести актуальный снапшот, иначе клиент сотрёт чипы при replaceMessageInList). Delete: `reactionRepository.deleteByMessageId` ДО удаления сообщения (FK без cascade). Тесты: `ReactionIntegrationTest` (идемпотентность PUT/DELETE, whitelist-400, 404-scoping, override-deny 403/owner-bypass, DM participant/третий-403, FK-чистка, edit-несёт-реакции) + `MessageServiceTest` (push `.reactions`, гейт, slug-400). 142 теста.
- **Reply + Forward сообщений (Discord-style):** **Reply** = `messages.replied_to_id BIGINT NULL` — **голый id БЕЗ FK/@ManyToOne** (в проекте нет ON DELETE CASCADE; self-FK потребовал бы detach-каскада при удалении оригинала). Висячий id детектится на чтении промахом батча → `ReplyInfo(messageId, authorId, authorUsername, preview≤100, createdAt, deleted=true, forward=null)`; 7-й компонент `ForwardInfo forward` — origin оригинала, КОГДА ОТВЕЧАЮТ НА ФОРВАРД (автор цитаты = переславший, и без этого снапшота цитата читалась бы как ответ автору исходного текста); 8-й `ReplyInfo reply` — РЕКУРСИЯ (карточки-матрёшки): цитата оригинала, когда оригинал сам reply — `replyOriginalsFor` многоуровневый frontier-батч (один `findAllById` на уровень, `MAX_QUOTE_DEPTH=5`, worst case 5 селектов на страницу истории; `null` = ОБРЕЗКА по капу, `deleted=true` = висячий id — не путать). `ForwardInfo` += 7-й `ReplyInfo reply` — ЗАМОРОЖЕННОЕ дерево цитаты источника, когда форвардили reply: колонка **`messages.forward_nested_reply TEXT`** (первый JSON-столбец проекта) + кодек `NestedReplyCodec` (Boot-бин `ObjectMapper` — **Jackson 3 / tools.jackson**, не com.fasterxml; reader LENIENT — будущее поле ReplyInfo не обкладывает старые строки; write-failure → null + лог: вложенность косметична и форвард из-за неё не падает; `capDepth` обязателен на КАЖДОМ persist — и режиме a, и клиентском b — иначе итерированный «reply→forward→reply→…» наращивает колонку без границ). Кросс-инстанс несёт дерево 10-м полем `originReply` в `ForwardMessageRequest` (модель доверия режима b). `create()` после save использует `replyOriginalsFor(List.of(message))` — единый путь глубины с history (3 strict-Mockito create-теста из-за этого требуют стаб `findAllById`). `ReadStateService.replyOriginalsOf` ОДНОУРОВНЕВЫЙ сознательно (mention-счётчику глубина не нужна; хелперы не «унифицировать»). `CreateMessageRequest` += `replyToId` (4-е, в конец); валидация — `findByIdAndChannelId` (чужой канал → 404, без утечки). Цитата ЖИВАЯ (правки оригинала видны при каждом re-fetch) — НЕ снапшот. **Reply-пинг (Discord):** `toResponse` синтезирует `MentionResponse("USER", origAuthorId, name, -1, -1)` (без @токена в тексте, оффсеты -1 = без спана; дедуп при текстовом @нике; self-reply без пинга). **ГРАБЛЯ: правило пинга синхронизировано в ДВУХ местах** — `MessageService.withReplyMention` (mentions в ответах) и `ReadStateService.countMentionsOf` (`replyPings` — pull-счётчик упоминаний); менять только вместе, иначе бейджи разъедутся с mentions. **N+1:** цитаты передаются в `toResponse(msg, reactions, Map<Long,Message> replyOriginals)` ПАРАМЕТРОМ (инвариант «toResponse не ходит в репозиторий» сохранён); `listByChannel`/`edit`/`countMentionsOf` батчат `findAllById`; пустой guard (`ids.isEmpty() → Map.of()`) держит старые strict-Mockito-тесты без новых стабов. `.edited`-пуш ответа несёт цитату (одиночный резолв через `replyOriginalsFor(List.of(msg))`); `.deleted`/`.edited` ОРИГИНАЛА сервером не пушится — клиент патчит цитаты сам (`withReply`). **Forward** = `POST /api/channels/{target}/messages/forward` (`ForwardMessageRequest`: sourceMessageId? + content/attachmentKey/attachmentFileName + originServer/originChannel/originAuthorId/originAuthorName/originCreatedAt). Два режима: (a) `sourceMessageId != null` — intra-инстанс, сервер копирует payload сам и выводит origin канонически (server name из `ServerProfileRepository.singleton().getName()` — Channel о сервере НЕ знает; channel из `source.getChannel().getName()`; автор/время из source) + гейт `READ_MESSAGE_HISTORY` на канале-источнике; (b) `sourceMessageId == null` — кросс-инстанс снапшот: поля на доверии клиента (инстансы независимы, федеративного протокола нет — модель спуфинга «From:» в email; утечки/обхода прав НЕТ, подделывается только атрибуция). **Анти-спуфинг: колонка `forward_trusted`** (Boolean) — true ТОЛЬКО в режиме (a); выводить из других колонок НЕЛЬЗЯ (снапшот может скопировать правдоподобный originServer) — клиент рендерит « · unverified» на снапшот-карточках. Гейт назначения: `SEND_MESSAGES` (+`ATTACH_FILES` при вложении) на TARGET; пуш на базовый топик `channel.{id}` (полное MessageResponse — НОВЫЙ суффикс не нужен, `DmSubscriptionInterceptor` не тронут). Форвард — НОВОЕ сообщение (автор = пересылающий), `repliedToId` форварда всегда null (карточка origin заменяет цитату); снапшот не живёт с оригиналом; форвард форварда показывает origin последнего хопа (без вложенной цепочки — v1). Вложение: intra — шарит `attachmentKey` (MinIO-объекты не удаляются с сообщениями), cross — клиент перекачивает через себя (presigned GET → temp → requestUpload+PUT на целевом инстансе). **Subset-forward (форвард одного изображения из лайтбокса-клиента):** в режиме (a) `ForwardMessageRequest.attachments` — СЕЛЕКТОР, а не payload: `null` = копировать ВСЁ (legacy, back-compat), non-null = НЕПУСТОЕ подмножество ключей источника (`MessageService.subsetOf`, калька edit()-проверок: пустой список / null-элемент / чужой key / дубль → 400 «Forward must select at least one attachment» / «Unknown attachment» / «Duplicate attachment»); при subset content НЕ копируется (текст писался про все картинки — «переслать картинку» уносит только файл; content-or-attachment остаётся удовлетворён непустым подмножеством), `forward_trusted` остаётся true. Wire-поля не менялись, но семантика сдвинулась → **парный деплой обязателен**: старый сервер молча игнорирует subset и пересылает ВСЕ вложения. Тесты: `MessageServiceTest` — фикстура `seedForwardSource` (lenient-стабы под strict-Mockito: 400-кейсы не доходят до happy-path стабов) + 5 кейсов (subset копирует только выбранное + content null + trusted; foreign-key/duplicate/empty-list → 400; null = всё). **Wire:** `MessageResponse` += 12-е `ReplyInfo reply` + 13-е `ForwardInfo forward` (nullable, в конец, синхронно с клиентским зеркалом; парный деплой обязателен). `ReplyInfo` сам += вложенный `forward` (7-й) + рекурсивный `reply` (8-й), `ForwardInfo` += `reply` (7-й), `ForwardMessageRequest` += `originReply` (10-й) — тоже wire-изменения, парный деплой распространяется на все. **History-курсор:** `GET .../messages?beforeId=` — страница строго СТАРШЕ курсора (`findByChannelIdAndIdLessThanOrderByIdDesc`, DESC→reverse как обычно); клиент использует для back-fill прыжка по цитате (кап 1000 на клиенте). Тесты: `ReplyForwardIntegrationTest` (цитата в create+history, кросс-канал 404, deleted-маркер, reply-на-форвард несёт origin в цитате, вложенные цепочки: 2 уровня / обрезка на 7 / deleted на глубине / форвард-reply снапшотирует дерево + переживает удаление корня / reply-на-форвард-reply несёт `forward.reply` / режим b с originReply + кап, read-state reply-пинг + дедуп, forward intra/snapshot + гейты + attachment-only, курсор) + `MessageServiceTest` (+7 кейсов, моки `ServerProfileRepository`/`NestedReplyCodec` — неиспользуемые моки легальны под STRICT_STUBS) + `NestedReplyCodecTest` (round-trip, unknown-field, деградация, capDepth по обоим рёбрам). 168 тестов.
- **Мультивложения (Discord-мозаика):** одно сообщение несёт **0..10 вложений** (`MessageService.MAX_ATTACHMENTS`, кап в сервисе: create и cross-instance forward; >10 → 400; элемент с blank key → 400 — ключ даёт только `POST /api/attachments`, клиент грузит байты до отправки). Хранение — **JSON-колонка `messages.attachments TEXT`** (второй JSON-столбец, паттерн `forward_nested_reply`): список `AttachmentRef{key, fileName}`; аттачи иммутабельны после create, всегда читаются с сообщением и копируются forward'ом дословно — JSON = один CPU-парс в `toResponse`, join-таблица потребовала бы батч-селектов и N вставок на каждый intra-forward. Кодек `AttachmentListCodec` (Boot-Jackson-3-бин; `read` НЕ возвращает null — blank/junk → `List.of()`; `write(null)→null`). **Wire (чистый разрез, парный деплой):** скалярные `attachmentFileName`/`attachmentUrl` (слоты 7–8) в `MessageResponse` ЗАМЕНЕНЫ на `List<AttachmentResponse{fileName,url}>` (слот 7, всего 12 полей; никогда null); `CreateMessageRequest` = `(content, List<AttachmentRef> attachments, replyToId)`, `ForwardMessageRequest` — список на слоте 3 (вместо скаляров, всего 10 полей). Сервер по-прежнему не терминирует байты и не валидирует типы (правило presigned-URL); `AttachmentController` без изменений — клиент зовёт его N раз. Intra-forward копирует ВСЕ ключи как есть (MinIO-объекты переживают сообщения); cross-instance — клиент перекачивает каждый файл. `previewOf` (reply-цитаты) фолбэчится на имя ПЕРВОГО аттача. **Миграция dev-Postgres** (одноразово, сервер остановлен, до старта нового билда): `ALTER TABLE messages ADD COLUMN attachments TEXT; UPDATE messages SET attachments = CASE WHEN attachment_key IS NULL OR attachment_key='' THEN NULL ELSE jsonb_build_array(jsonb_build_object('key', attachment_key,'fileName', attachment_file_name))::text END; ALTER TABLE messages DROP COLUMN attachment_key; ALTER TABLE messages DROP COLUMN attachment_file_name;` — имена полей обязаны совпадать с компонентами `AttachmentRef` (ddl-auto=update колонки не дропает — руками). Тесты: `AttachmentListCodecTest` (round-trip/blank/junk/null/never-null) + `MessageServiceTest` (мультивложение с подписью каждого URL, 11→400, blank key→400, intra-forward копирует все, реальный кодек через `@Spy` — мок заставил бы стабить каждый тест) + переписанные на новый wire интеграционные. **Edit-удаление аттачей (Discord «крестик»):** `EditMessageRequest` = `(@Size content, List<AttachmentRef> attachments)` — `@NotBlank` на content УБРАН (удаление последнего аттача у attachment-only шлёт пустой текст; content-or-attachment валидируется по ИТОГУ в `edit`, как create/forward; валидация ДО мутаций — 400 не трогает строку/editedAt); `attachments` null = не трогать, список = заменить ровно на него, обязан быть ПОДМНОЖЕСТВОМ текущих ключей (чужой/дубль → 400); MinIO-объекты удалённых аттачей НЕ удаляются (форварды шарят ключи). `AttachmentResponse` += 3-е поле `key` в конец (стабильная идентичность аттача — URL переподписывается, индексы съезжают; единственное место `new AttachmentResponse(` — `toResponse`). Пуш — тот же `.edited`. Смоук-гонка: mid-edit WS-эхо/реакция перерисовывают ячейку — клиент держит мозаику под полем repair-строкой в updateItem. **Видео-превью (клиентская генерация, сервер без ffmpeg):** `AttachmentRef` += 4-е/5-е поля `thumbnailKey` (@Size 512) + `durationMillis` (@PositiveOrZero; legacy 2-арг конструктор для тестов) — превью-кадр+длительность КЛИЕНТ извлекает при загрузке (локальный файл, свой GStreamer) и заливает как обычный объект (`…-preview.jpg`) через тот же `POST /api/attachments`; оба поля nullable навсегда (старые строки, HEVC, неудачный grab → клиент рендерит плашку без превью), lenient-кодек читает pre-preview-строки с null. `AttachmentResponse` += 4–6-е поля `thumbnailKey`/`thumbnailUrl`/`durationMillis`: `toResponse` подписывает превью-URL отдельным `presignedGetUrl(thumbnailKey)` (+1 CPU-подпись на видео-аттач); fresh-link `GET /api/attachments/link?key=` БЕЗ правок закрывает и превью-ключ — native-LIKE гоняет по всему JSON-тексту колонки, не по конкретному полю (тест `videoPreviewFieldsRoundTripAndThumbnailKeyResolvesFreshLink`). Edit/subset-forward матчаут по `key()` — превью-поля едут пассажирами; кросс-инстанс forward: превью перекачивает КЛИЕНТ (`reuploadAttachment`). 183 теста.
- Тесты: `AuthFlowIntegrationTest` (вкл. refresh/ротацию/переиспользование), `ChannelMessageFlowIntegrationTest`, `UsersPresenceIntegrationTest`, `AttachmentIntegrationTest` (MockMvc, вкл. fresh-link: 401/404/переподписка), `UserAvatarIntegrationTest` (MockMvc, two-phase avatar upload), `AuthChannelInterceptorTest`, `MessageServiceTest` (Mockito, пуш + валидация content-или-attachment), `PresenceTrackerTest`, `VoiceMembershipTrackerTest` (вкл. server-mute/deafen state + kick), `JwtTokenProviderTest`, `AppTest`, `RbacIntegrationTest` (MockMvc, roles/overrides/hierarchy/ban/attach/connect/deleteRole), `MentionParsingIntegrationTest` + `MentionParserTest` (парсинг `@`-упоминаний + gating), `ReadStateIntegrationTest` (unread/mention-count + ack), `MessageEditDeleteIntegrationTest` (edit/delete гейты + DM), `ReactionIntegrationTest` (реакции: идемпотентность/whitelist/гейты/DM/FK-чистка), `ReplyForwardIntegrationTest` (reply/forward: цитаты/пинг/гейты/курсор), `AttachmentListCodecTest` (JSON-колонка аттачей), `RefreshTokenCleanupTest` (scheduled-чистка удаляет в собственной транзакции), `JanusVoiceAccessServiceTest` (токен-кап/revocе-скоуп/display-mатчинг/audio-mid/HMAC/ensureRoom). `mvn test` — 214 тестов, всё зелёное. (Грабля MockMvc: `jsonPath("$.x").exists()` считает ЯВНЫЙ null отсутствующим — noop-сигнер даёт `{"url":null}`; проверять через `JsonPath.read(...)` + `assertNull`.)
- Периферия (`env/docker-compose.yml`): postgres, minio, janus (`canyan/janus-gateway`), coturn — поднимаются и проверены.

- **`TURN_EXTERNAL_IP` в `env/.env` должен быть LAN-IP машины** (например `192.168.0.78`), не `127.0.0.1` и не публичный IP. Coturn в Docker рекламирует relay-кандидаты с этим IP; клиент (на хосте) должен до них достучаться. Изначально стоял `127.0.0.1` — не работал для TURN-relay. **Полная карта граблей TURN-на-Docker-Desktop/Windows (живой смоук 2026-09, месяц копания):** (1) libwebrtc **молча выбрасывает loopback-адрес TURN-сервера** — `turn:localhost:…` в `/api/call/config` даёт НОЛЬ ICE-кандидатов в RELAY-режиме (симптом: звонок «устанавливается», ICE навсегда CHECKING, звука нет; `TURN_HOST` должен быть LAN-IP); (2) даже с LAN-IP: через UDP port-proxy coturn видит каждого клиента как gateway `172.x.0.1` — TURN-permission-механика режет весь peer-трафик (allocation успешен, relay-кандидаты собираются, `rp=0/sp=0` — медиа не проходит ни пакета); (3) `network_mode: host` не экспонирует порты на Windows-хост (WSL2-namespace), UDP напрямую на docker-IP контейнера с Windows тоже не проходит. **Итог: полноценный RELAY-смоук на этой dev-машине невозможен** — только на реальном Linux-хосте или с двух машин через достижимый TURN. Сама клиентская ICE-restart-механика при этом доказана логами (лестница рестарт-офферов + «answering in place» у принимающего).

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

- **User** — учётная запись, учётные данные, профиль. Связан с `Role` многие-ко-многим (`user_roles`); несёт `banned` (бан).
- **Channel** — канал верхнего уровня (текстовый / голосовой), принадлежит инстансу.
- **Message** — текстовое сообщение (с ссылкой на вложение в MinIO, если есть).
- **Role** — роль инстанса (name, color, position-иерархия, permissions-bitmask, mentionable, hoist, `kind`). Built-ins: `@everyone` (неявно у всех, не в `user_roles`), `OWNER` (суперпользователь, single-holder, protected).
- **ChannelRoleOverride** — per-канальный allow/deny per роль (Discord-style private channels).

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
- [x] **Смена пароля (self-service):** `POST /api/users/me/password` (body `ChangePasswordRequest{oldPassword, newPassword}`; `@AuthenticationPrincipal`; `UserController` → `AuthService.changePassword` `@Transactional`): verify old (`passwordEncoder.matches`) → BCrypt-encode new → save → **revoke всех refresh-токенов юзера** (`RefreshTokenRepository.findByUser` — новый derived-метод, FK `user_id` уже был) → `issueTokens(user, newFamilyId())` (свежий access+refresh как login). Возвращает `AuthResponse` — текущая сессия продолжает без re-login, прочие устройства разлогиниваются (их refresh отозван). **Tradeoff:** access-JWT stateless (8ч, без denylist) — отозвать нельзя; текущая сессия получает новый access в ответе, прочие access умрут ≤8ч, а их refresh мрёт сразу. wrong old → **400** (не 401 — чтобы 401 означал expired-access → клиентский silent-refresh); `new==old` → 400; `@Size(min=8,max=100)` на newPassword → 400. Тесты в `AuthFlowIntegrationTest`. 118 тестов.
- [ ] **Server-side logout**: `POST /api/auth/logout` — отзыв предъявленного refresh-токена текущей сессии (`AuthService.logout` → `stored.setRevoked(true)`, идемпотентный, `204 No Content` всегда — не раскрывать, существовал ли токен). Сейчас выхода на стороне сервера нет: клиент (`oak-client`) выбрасывает токен локально, а строка в БД живёт до истечения срока. Опционально: `logout-all` (отзыв всей семьи = «выйти со всех устройств», переиспользует family-механику reuse-detection) и вызов эндпоинта из `oak-client` по кнопке выхода.
- [ ] **Remote provisioning («одна кнопка»: клиент поднимает сервер сам) — фаза 1, one-line server.** Цель: развёртывание инстанса OAK на любой Linux-машине с Docker одной командой вместо ручной сборки. Предусловие всего пункта — **контейнеризация oak-server**: сейчас Spring Boot работает на хосте, а `env/docker-compose.yml` поднимает только периферию (postgres/minio/janus/coturn); нужен `Dockerfile` + oak-server-сервис в compose (+ сборка образа в CI или publish). Затем — install-скрипт (curl|bash на сервере), который: кладёт compose-файл в `/opt/oak/`, **генерирует `.env` со случайными секретами** (не dev-дефолты `oakpass`!), сам определяет `TURN_EXTERNAL_IP` = IP хоста (сейчас ручная грабля, см. «Грабли»), зовёт `docker compose up -d` и поллит health-check. Идемпотентность: живой `/opt/oak` → «adopt» (подключиться), никогда `down -v`. Клиент после этого просто подключается обычным Add-server (первый register = OWNER, уже работает). Ограничение v1 честно пометить: dev-сетап без TLS → **только LAN/VPN**; production-TLS (reverse proxy, https `publicEndpoint` для MinIO) — отдельный пункт. Файрвол (8080 REST/WS, 8088 Janus REST, 9000 MinIO, 3478+UDP-диапазон TURN) — показывать список портов в выводе скрипта, не рулить `ufw` автоматически.
- [ ] **Remote provisioning — фаза 2, SSH-диалог из клиента.** Диалог «Set up a new server» в `oak-client`: IP, SSH-порт, SSH-юзер, пароль/ключ → клиент по SSH (чистый Java, например `com.hierynomus:sshj`) прогоняет зонд (ОС, Docker есть/нет; нет → вежливый отказ, офер «установить через get.docker.com» только с явного согласия), выкладывает файлы фазы 1, ждёт health-check и подключается (register/login). Соображения: SSH-креды не персистить (в идеале ключ); обновление сервера = тот же SSH-путь `docker compose pull && up -d`; зависит от фазы 1 (клиент лишь оркестрирует её артефакты).
- [x] **Поиск по сообщениям + курсоры aroundId/afterId истории (2026-09-18, задача #481, ДЕПЛОЙ ПАРНЫЙ со space-client).**
  Юзер-решения: матчинг ПОДСТРОКОЙ как Discord (никакого tsvector/стемминга — «прив» найдёт
  «привет», «запуск» не найдёт «запускали»; для подстрок pg_trgm честнее tsvector-лексем);
  скоуп = канал + весь сервер; фильтры v1 = текст + author. **Эндпоинт** `GET
  /api/search/messages?q=&channelId=&author=&beforeId=&limit=` (`SearchController` →
  `MessageService.searchMessages`, ответ `SearchMessagesResponse{total, results}` — total
  БЕЗ курсора, константа на всех страницах; results newest-first, курсор beforeId, limit
  дефолт 25 / кап 50): `channelId` задан — канальный скоуп (DM работает, гейт вырождается в
  participant-check); не задан — ВЕСЬ СЕРВЕР (паттерн `ReadStateService.snapshot`:
  `findByTypeNotInOrderByIdAsc(DM,GROUP_DM)` → фильтр type==TEXT + `hasInChannel(READ)`;
  скрытые каналы ловит VIEW-мастер-гейт, DM НЕ возвращаются НИКОМУ — даже участнику).
  `q`/`author` оба опциональны, оба пустые → 400; unknown author → пустой результат (total 0,
  не 404 — нет оракла для перебора логинов). Native-запросы в `MessageRepository`
  (`searchMessages` + `countSearchMessages`): `(COALESCE(content,'') ILIKE :pattern OR
  COALESCE(attachments,'') ILIKE :pattern)` — имена файлов аттачей матчатся из JSON-текста;
  sentinel-параметры `authorId=0`/`beforeId=Long.MAX_VALUE` вместо NULL (типизация NULL в
  native unreliable, один shape запроса); COALESCE держит attachment-only (content NULL)
  в author-only поиске. Escape LIKE-метасимволов — Java-хелпер `MessageService.likePattern`
  (`\`→`\\`, `%`→`\%`, `_`→`\_`, обёрнутое в %…%; backslash = default ESCAPE в PG и H2,
  клауз не нужен). H2 MODE=PostgreSQL переваривает ILIKE (тесты идут на нём; кириллический
  кейс-фолд ILIKE проверяется живым смоуком, не H2). **Курсоры истории** (`MessageController.list`
  += `aroundId`/`afterId`, максимум ОДИН из beforeId/aroundId/afterId → 400): `aroundId` — окно
  limit-сообщений ЦЕНТРИРОВАННОЕ на сообщении (older=(limit-1)/2 DESC+reverse + target + newer;
  прыжок по результату поиска; чужой/удалённый id → 404 `findByIdAndChannelId`-скопинг);
  `afterId` — страница строго младше курсора ASC (`findByChannelIdAndIdGreaterThanOrderByIdAsc`,
  без reverse — догрузка вниз от окна). **Ack-харден:** `ReadStateService.ack` стал
  МОНОТОННЫМ (новое lastRead < текущего → no-op) — ack из частичного окна (клиент прыгнул
  в середину истории поиском) больше не откатывает read-указатель и не воскрешает unread.
  **ИНДЕКСЫ** накатываются МИГРАЦИЕЙ Flyway `V2__message_search_trgm.sql` (pg_trgm +
  GIN content/attachments, идемпотентные IF NOT EXISTS; см. пункт Flyway ниже) — ручная
  раскатка отменена. pg_trgm-GIN эффективен от ~3 символов паттерна (клиентский минимум 2
  символа; 1–2-символьный запрос может уйти в seq scan — норма на наших объёмах); H2 индексов
  не видит — быстродействие проверять только живым `EXPLAIN ANALYZE`. Тесты:
  `SearchIntegrationTest` (8: 401; подстрока+кейс+total-константа+курсор; кириллица same-case;
  escape `_`; attachment-fileName-hit; author-фильтр±unknown+400; гейты hidden/DM/серверо-широкий;
  around/after-окна+404+400; ack-монотонность) — 284 зелёных. Смоук pending.

- [x] **Flyway владеет схемой (#482, 2026-09-18, сервер-only, wire не менялся).**
  Юзер-решения: Flyway, НЕ Liquibase (один целевой Postgres — XML-абстракция не даёт ничего;
  SQL-файлы как есть для pg-специфики; preConditions — концепция Liquibase, в Flyway та же
  задача решается baseline-on-migrate + идемпотентным IF NOT EXISTS); полный режим:
  **ddl-auto: update → validate** (Hibernate больше не доводит схему), изменения схемы —
  ТОЛЬКО миграции `src/main/resources/db/migration/V*.sql`. **Миграции:** `V1__baseline.sql`
  = pg_dump живой dev-схемы (`--schema-only --no-owner --no-privileges --no-comments`,
  санитайз: вырезаны SET-шапка, `SELECT pg_catalog.*`, и ОБЯЗАТЕЛЬНО psql-метакоманды
  `\restrict`/`\unrestrict` — JDBC их не исполняет; проверен прогоном на throwaway-базе
  oak_v1check: 13 таблиц, 0 ошибок; мёртвая users.email ВЫЧИЩЕНА из дампа — юзер-решение
  «чистый V1» вместо пары V1(с-email)+V3(дроп); колонку из живой базы снесла первая V3 до
  удаления, повторного ручного ALTER не потребовалось); `V2__message_search_trgm.sql` —
  индексы поиска #481 (БЕЗ CONCURRENTLY — миграция транзакционна; для большого прода:
  per-script `<file>.sql.conf` executeInTransaction=false + CONCURRENTLY). **Конфиг:**
  dev `spring.flyway {enabled, baseline-on-migrate: true,
  baseline-version: 1}` — живая база ПОГЛОЩАЕТ V1 без исполнения (baseline-метка), чистая
  исполняет; test-профиль `flyway.enabled: false` (H2 не умеет pg-specific V1; тестовая
  схема остаётся create-drop — миграции H2 не покрывает, осознанное ограничение; их
  проверяет живый старт). **pom:** `spring-boot-flyway` (Boot 4 modular-артефакт, тянет
  flyway-core 12.4.0) + `org.flywaydb:flyway-database-postgresql:12.4.0` runtime с ЯВНОЙ
  версией (BOM её не ведёт; **ГРАБЛЯ: в Flyway 12 артефакт переименован —
  flyway-database-POSTGRESQL, старый flyway-database-postgres из central УДАЛЁН (404)**;
  версия обязана локстепом совпадать с flyway-core — property flyway.version).
  **ДИСЦИПЛИНА (заменила «рука-миграции»):** любое изменение сущности = миграция V(n+1)
  в том же коммите; validate на живом старте — страховщик дрейфа сущность↔миграции;
  сидеры (RoleSeeder/NicknameSeeder/ensureEveryoneViewChannel) остаются — это DATA-бойстрап,
  не схема. **Смоук ПРОЙДЕН вживую (2026-09-18):** (1) живая dev-база — baseline 1 →
  migrate 2 → Started App (validate чист, 7 юзеров целы); оба trgm-индекса в pg_indexes,
  flyway_schema_history = baseline+2 success, users.email отсутствует; (2) ЧИСТАЯ база
  oak_v1check (createdb + POSTGRES_DB=env) — «Empty Schema» → V1-baseline исполнен с нуля →
  2 → Started App (validate против дампа прошёл — V1 пригоден для новых инстансов),
  база дропнута; (3) повторный старт — «Schema is up to date. No migration necessary».
  Попутно накатились индексы поиска #481 — ручная раскатка не понадобилась вовсе.
  **Правка «чистый V1» (юзер-решение, тот же день): V3 удалён, email вырезана из V1** —
  перебазлайн живой базы через `DROP TABLE flyway_schema_history` + рестарт (re-baseline 1
  + идемпотентная V2 IF NOT EXISTS — безопасный путь без flyway-repair). ГРАБЛИ ПО ПУТИ:
  (а) **stale target/classes** — удаление файла миграции из src НЕ удаляет его из
  target/classes (Maven не вычищает умершие ресурсы): первый перезапуск исполнил СТАРУЮ
  V3 из classpath; лекарь = `mvn clean` при любом удалении/переименовании миграции;
  (б) TaskStop не убивает форкнутый spring-boot:run-JVM — дерево через
  `taskkill /PID <8080-listener> /T /F`.
- [x] **Pinned messages + discussions/threads (2026-09-22, ДЕПЛОЙ ПАРНЫЙ с space-client и зеркалом oak-client).** Две фичи одним раундом. **(A) ПИНЫ (Discord-модель):** `Message` += `pinned boolean NOT NULL` + `pinned_by` (id) + `pinned_by_name` (ДЕНОРМАЛИЗОВАННЫЙ ник пинившего — forwardAuthorName-идиома, инвариант «toResponse не ходит в репозитории» сохранён) + `pinned_at` — четыре колонки пишутся/чищатся ВМЕСТЕ в `MessageService.pin/unpin`. Эндпоинты `PUT|DELETE /api/channels/{id}/messages/{messageId}/pin` (гейт `requireInChannel(MANAGE_MESSAGES)` — в DM ветка hasInChannel вырождается в participant-check, т.е. ЛЮБОЙ участник пинит, ровно Discord; оба возвращают 200 с ПОЛНОЙ строкой) + `GET …/messages/pinned` (литеральный сегмент `pinned` — не конфликтует с `/{messageId}`-маршрутами, Spring предпочитает литерал; ASC по pinnedAt — карточка клиента берёт ПОСЛЕДНИЙ = свежайший пин). Идемпотентность: повторный пин НЕ переатрибутирует (оригинальные pinnedBy/pinnedAt живут), анпин незакреплённого = no-op broadcast; **кап `MAX_PINS_PER_CHANNEL=50`** → 400 «Pin limit reached (50)» (ЮЗЕР-РЕШЕНИЕ: ошибка, НЕ вытеснение — «важное» не отваливается молча). Пуш — СУЩЕСТВУЮЩИЙ `.edited`-топик полной строкой (интерцептор не тронут). **GC-ИММУНИТЕТ: `findIdsOldestFirst` получил `where m.pinned = false`** — ночной purge физически не может выбрать закреп (кап 50 = бюджет неуязвимости); событийная чистка не тронута (пин умирает вместе с сообщением честно). `MessageResponse` += 13–15-е поля В КОНЕЦ (`pinned, pinnedBy(строка!), pinnedAt`) — ЗЕРКАЛО oak-client `net/dto/MessageResponse.java` ОБНОВЛЕНО В ТОМ ЖЕ КОММИТЕ (withReactions/withReply переносят три поля; FxToolkitTest арности). Тесты: `PinIntegrationTest` (5: round-trip+wire-поля; ASC-порядок; гейты сервер/DM/чужой; кап-400+per-channel; 404-scoping+пин-умирает-с-сообщением) + `MessageServiceTest` (+5: stamping/edited-пуш; кап; re-pin не крадёт атрибуцию; unpin чистит; гейт) + `StoragePurgeFlowTest.pinnedMessagesSurviveThePurge` (закреплённый старейший выживает, 3 остальных снесены). **(B) ОБСУЖДЕНИЯ (НЕ-Discord UI — см. юзер-спеку):** обсуждение = `Channel` типа `DISCUSSION` (дописан В КОНЕЦ enum; V4 DROP/ADD CHECK `channels_type_check` — enum-CHECK грабля ddl-auto) + сущность-спутник `discussions` (V4): `channel_id` UNIQUE 1:1, `parent_channel_id`, `origin_message_id` UNIQUE (один тред на сообщение), ЗАМОРОЖЕННЫЙ снапшот исходника `origin_snippet(200)/origin_author_id/origin_author_name/origin_created_at` (ForwardInfo-дисциплина — плитка переживает удаление/GC исходника) + `last_message_at`. Сообщения треда — ОБЫЧНЫЕ messages в этом канале: история/правки/реакции/удаления/аттачи работают даром; в историю родителя НЕ попадают. **Права ДЕЛЕГИРОВАНЫ родителю:** `AuthorizationService.hasInChannel` + `resolveChannelMask` — ветка DISCUSSION → резолв через parent (один хоп, рекурсии нет — родитель всегда TEXT; осиротевшая строка = deny); это же закрывает интерсептор (DISCUSSION-id в family проходит через hasInChannel) и close-гейт. **Трекинг** = `channel_participants` на DISCUSSION-канале: создатель участник; `publishActivityCreated` (хук из `MessageService.create` при type==DISCUSSION, после broadcast; реплеи выходят раньше) авто-джойнит автора («написал раз → отслеживаешь») + обновляет lastMessageAt + пушит ACTIVITY со свежим `countByChannelId`. Эндпоинты (`DiscussionController`, ПОЛНЫЕ пути на методах — не @{id}-коллизия DmChannelController): `POST|GET /api/channels/{id}/discussions` (старт: parent TEXT→400-иначе, origin в родителе→404 findByIdAndChannelId, дубль→**409** (юзер-решение: конфликт, клиент ловит и открывает существующий), гейт SEND_MESSAGES; лист канала = все треды с tracked-флагом), `GET /api/discussions/mine` (мои, lastMessageAt desc, недоступные родителем выкинуты), `GET /api/discussions/{id}`, `PUT|DELETE /api/discussions/{id}/track` (идемпотентные 204, гейт READ родителя), `DELETE /api/discussions/{id}` (**close для ВСЕХ**, гейт MANAGE_MESSAGES через делегирование; FK-safe teardown реакции→сообщения→участники→read-states→discussions-строка→канал + orphan-аттач-GC (событийный ярус инлайн — БЕЗ зависимости на MessageService, цикл create-хук↔orphan-GC); пуш ТОЛЬКО `REMOVED` на родительский `.discussions` — bare-Long на `channel.{id}.deleted` ОТКЛОНЁН: id сообщений и каналов в одном числовом пространстве, неоднозначно). **Топики:** НОВЫЙ суффикс `.discussions` на РОДИТЕЛЬСКОМ канале (`DiscussionEvent{type CREATED/ACTIVITY/REMOVED, discussionId, parentChannelId, originMessageId, lastMessageAt, replyCount}` — counts-only, без тел), **regex интерцептора РАСШИРЕН `|\.discussions`** (security: иначе world-readable перебором id); гейт тот же READ родителя. **Исключения:** `ChannelService.list` исключает DISCUSSION из `/api/channels`; `ChannelService.rename` 400 на DISCUSSION; `ChannelService.delete` сносит треды канала через общий `DiscussionService.deleteDiscussionsOf`; `ReadStateService.snapshot/seedInitialReadStates` скипают DISCUSSION (непрочитанные тредов НЕ утекают в бейджи через делегирующую ветку), `ack` треда → 400 «Discussions are not read-state tracked»; поиск: channel-scoped на треде → 400 «Discussions are not searchable», server-wide и так ходит только по TEXT. **СОПУТСТВУЮЩИЙ FK-БАГ (латентный, найден ревью):** `ChannelService.delete`/`DmService.deleteChannelCompletely` бёрк-делили `messageRepository.deleteByChannelId` НЕ почистив реакции — FK `message_reactions.message_id` (без cascade) валит удаление канала с любым прореагированным сообщением; НОВЫЙ `MessageReactionRepository.deleteByChannelId` (JPQL bulk `@Modifying` — БЕЗ него запрос исполняется как select и падает в рантайме) вызывается ПЕРВЫМ во всех трёх местах (третье — discussion close). Известное ограничение v1: счётчик replyCount в ACTIVITY не декрементируется при удалении сообщения в треде (ста́лесть до следующего события — принято). Тесты: `DiscussionIntegrationTest` (8: snapshot-поля+tracked-создатель+409; гварды 400/404/DM/VOICE; пост в тред авто-трекает и НЕ трогает историю родителя; mine-сортировка по активности; track/untrack идемпотентны и персональны; close-гейт+полный teardown; исключения каналы/read-states/поиск; удаление родителя сносит треды) + `DiscussionServiceTest` (5: CREATED-пуш; гварды create; ACTIVITY авто-джойн+count; close-порядок InOrder+REMOVED; close-гейт) + `ChannelSubscriptionInterceptorTest` (4: .discussions читатель/чужой; DISCUSSION-id base family; DM-семантика не тронута; не-family суффиксы passthrough) + `MessageServiceTest.createIntoADiscussionChannelFiresTheActivityHook`. 313 тестов. Смоук pending. **СМОУК-ФИКС 1 (живый 500 на старте треда): V4 создала `discussions` БЕЗ identity-блока на `id`** — INSERT падал «null value in column "id" violates not-null constraint»; H2-тесты слепы против такого промаха (test-профиль регенерирует схему ИЗ сущностей, живой Postgres — Flyway-owned; тот же класс, что enum-CHECK). **ПРАВИЛО: новая таблица в миграции под @GeneratedValue(IDENTITY)-сущность ОБЯЗАНА нести блок `ADD GENERATED BY DEFAULT AS IDENTITY` (V1-стиль)**. Фикс — `V5__discussions_id_identity.sql` (V4 уже применена на живой базе, правка в месте = Flyway checksum-mismatch; таблица пуста — backfill не нужен).
- [x] **Интринсивные размеры медиа на проводе (2026-09-25, медия-раунд фазы B; ДЕПЛОЙ
  ПАРНЫЙ со space-client — oak-client БОЛЬШЕ НЕ ПОДДЕРЖИВАЕТСЯ и зеркало НЕ обновляется).**
  `AttachmentRef` += 5-е/6-е поля `width`/`height` (nullable @PositiveOrZero) — интринсивные
  пиксельные размеры медиа, ИЗМЕРЯЕМЫЕ КЛИЕНТОМ при аплоаде (видео — тот же декод, что grabbing
  превью; картинки — createImageBitmap); сервер — чистый passthrough (модель доверия как у
  cross-instance форвардов: считает отправитель, сервер хранит/эхоит). Назначение: клиент
  резервирует ТОЧНЫЙ layout-бокс (aspect) до загрузки байтов — аттач-тяжёлые каналы больше
  не рефлаят под вьюпортом. Дублируется как persisted-форма в JSON-колонке messages.attachments
  (AttachmentListCodec lenient — старые строки читаются с null). `AttachmentResponse` += 7-е/8-е
  поля (зеркально). Convenience-конструкторы: legacy 2-арг + pre-dimension 4-арг (видео-превью).
  Legacy-строки без размеров = null навсегда (клиент доучивает кэшем после первого декода —
  клиентская сторона в space-client/CLAUDE.md). Тест: AttachmentIntegrationTest
  .attachmentDimensionsRoundTripAndAbsentStaysNull (create+history passthrough; отсутствующие
  поля = отсутствуют в JSON). 320 тестов. Wire не ломается о старый клиент (новые поля в конце;
  JSON.parse без строгого маппера).
- [x] **Кастомные эмодзи, фаза 1 из 3 — серверный каталог (2026-09-25; ДЕПЛОЙ: сервер первым,
  клиент следом — НЕ строго парный: старый клиент на новом сервере просто не знает о фиче,
  новый клиент на старом скрывает её по 404 каталога).** Плоский сервер-скоуп каталог:
  сообщения НЕсут `:name:` как plain text (MessageResponse НЕ тронут), клиент резолвит
  токены по живому каталогу — модель Discord, удаление эмодзи деградирует старые строки в
  plaintext (без FK, rename-модель-трейдофф). Фазы: (1) каталог+пикер+настройки — ЭТОТ
  раунд; (2) стикеры с паками; (3) кастомные эмодзи в реакциях. **Сервер:** миграция
  `V6__custom_emojis.sql` (таблица + ОБЯЗАТЕЛЬНЫЙ identity-блок — V4-урок) + сущность
  `CustomEmoji` (name `[a-z0-9_-]{1,32}` ASCII нарочно — клиентский парсер `:name:`
  использует тот же класс; UNIQUE name + UNIQUE file_key; uploaded_by FK) +
  `EmojiRepository` (existsByName/existsByFileKey/findAllByOrderByNameAsc). Сигнер +=
  `newEmojiKey` (префикс `custom-emojis/`; ×3 interface/MinIO/Noop). `EmojiController`
  (/api/emojis): GET — любой авторизованный (render data как /api/server); POST /slot —
  гейт MANAGE_EMOJIS (право УЖЕ было в enum с «future»-комментарием) + расширение
  png/gif/webp; POST — @Transactional гейт, existsByName→409 + DataIntegrityViolation→409
  (гонка), кап 200→400, fileKey обязан начинаться с `custom-emojis/`→400 (слот минтит
  только наш сигнер), НЕ проверяет существование объекта (аватарный confirm тоже не
  проверяет; noop-сигнер тестов); DELETE /{id} — гейт, 404, row → best-effort
  objectStore.delete(fileKey). Каждая мутация пушит PING `/topic/emojis` (bare
  Long-timestamp, каналы.changed-прецедент; сервер-скоуп топик ВНЕ channel-интерцептора —
  regex не трогаем). **/link-OR:** `AttachmentController.freshLink` += `||
  emojiRepository.existsByFileKey(key)` — ИМЕННО В КОНТРОЛЛЕРЕ, НЕ в
  MessageService.attachmentExists (иначе семантика GC-ярусов молча расширяется и ломает
  MessageServiceTest-моки) — фреш-ссылки для эмодзи-ключей работают через существующий
  /api/attachments/link. **GC-иммунитет структурный:** attachment-свип листит ТОЛЬКО
  attachments/ — custom-emojis/ невидим (как аватары); НО брошенные слоты (ключ минтнут,
  строка не создана) текли бы вечно → `AttachmentObjectStore` += `listCustomEmojiObjects()`
  (MinIO listByPrefix-рефактор общего helper'а; Noop — пусто) + ВТОРОЙ цикл в
  StorageGcService.sweepOrphans (та же mtime-grace, existsByFileKey, ТЕ ЖЕ счётчики —
  StorageGcReport не меняется; конструктор += EmojiRepository → StorageGcServiceTest-фикс).
  Тесты: EmojiIntegrationTest (10: 401/403-плебей/слот-префикс+null-url-под-noop/
  расширение-400/round-trip+сорт+uploadedBy/лестница-имени/дубль-409/чужой-префикс-400/
  кап-200/д delete-204→404/линк-OR) + StorageGcServiceTest.emojiSweepReclaimsAbandonedSlots...
  332 теста. Клиентская половина — space-client/CLAUDE.md.
- [x] **Кастомные стикеры с паками, фаза 2 из 3 (2026-09-29, код готов, смоук pending;
  ДЕПЛОЙ: сервер первым — V7 + аддитивные эндпоинты + append-only поле MessageResponse;
  старый клиент рендерит стикер-only строку пустой (переходно, как attachment-only до
  фазы 1); клиентская половина — space-client/CLAUDE.md).** Юзер-решения: гейт управления —
  СУЩЕСТВУЮЩИЙ `MANAGE_EMOJIS` (один гейт на весь «каталог выражений», Discord
  MANAGE_EXPRESSIONS-коллапс без нового бита); отправка стикера — только SEND_MESSAGES
  (НЕ ATTACH_FILES — стикер не файловый аттач, как `:name:`-токен). **Ключевой дизайн:
  стикер в сообщении ДЕНОРМАЛИЗОВАН** — `messages` += `sticker_name` (32) +
  `sticker_file_key` (512), обе nullable, БЕЗ FK (dangling by design как replied_to_id):
  toResponse остаётся «никогда не трогает репозитории» (пресайн = CPU, pinnedByName-идиома),
  intra-instance forward копирует verbatim бесплатно, удаление стикера из каталога
  деградирует старые строки в клиентский tombstone (тот же принятый трейдофф, что у эмодзи).
  Wire (append-only): `CreateMessageRequest` += 5-е поле `Long stickerId`;
  `MessageResponse` += 16-е поле `StickerRef{name,key,url}` (прямые конструкции в
  DiscussionServiceTest/MessageServiceTest дописаны null-аргументом). **Три
  content-or-X-гейта расширены** (create/edit/forward — «content, an attachment or a
  sticker»; edit гейтит по `message.getStickerName()==null`, стикер неизменяем; forward:
  полный копи несёт стикер, subset-ветка («forward the image») и кросс-инстанс — дропают,
  клиент компенсирует ре-аплоадом картинки). `previewOf` (reply-цитаты): blank → аттач-name
  → `[Sticker] name`. Search НЕ тронут (COALESCE-запрос: author-only паттерн '%%' матчит
  пустой контент — стикер-only строки выживают, как attachment-only). **Сервер:**
  `V7__stickers.sql` (sticker_packs + stickers c identity-блоками КАЖДАЯ; UNIQUE(name)
  пака + UNIQUE(pack_id,name) стикера (имя уникально В ПАКЕ, не глобально) +
  UNIQUE(file_key); FK pack без cascade — пак-удаление сносит строки явно, объекты
  best-effort ПОСЛЕ; ALTER messages ×2). Сущности StickerPack/Sticker (кальки CustomEmoji),
  репозитории (existsByFileKey/existsByPackIdAndName/findByPackId/findAllByOrderByNameAsc).
  `StickerPackController` (/api/sticker-packs): GET — весь каталог паками с ВЛОЖЕННЫМИ
  стикерами (2 запроса + in-memory group-by, без N+1), любой авторизованный; POST — гейт,
  trim, existsByName→409, кап 20→400; DELETE /{id} — гейт, 404, стикеры → пак → объекты.
  `StickerController` (/api/stickers): POST /slot (гейт + png/gif/webp → newStickerKey);
  POST (пак 404, дубль-в-паке 409, кап 200→400, префикс stickers/→400, DIV→409);
  DELETE /{id}. Пинги — bare-Long на `/topic/stickers` (интерсептор не трогаем — regex не
  матчит). Сигнер ×3 += `newStickerKey`; `AttachmentObjectStore` += `listStickerObjects()`;
  StorageGcService += ТРЕТИЙ sweep-цикл (та же grace/счётчики, existsByFileKey; конструктор
  += StickerRepository); `/link`-OR += стикер-ключи (в контроллере). Стикер-ключи НЕ в
  attachments-JSON → delete/deleteUnreferencedObjects не трогаем (жизненный цикл объекта
  принадлежит каталогу). Тесты: StickerIntegrationTest (20 — полная лестница + сообщение со
  stickerId денормализовано в ответе/истории/forward, unknown→404, edit пустым контентом
  200, reply-preview, author-only search, линк-OR) + MessageServiceTest += юнит стикер-гейта
  + StorageGcServiceTest.stickerSweep... — **355 тестов** (было 333).
- [x] **Кастомные эмодзи в реакциях — фаза 3 из 3 (2026-09-30, код готов, смоук pending;
  ДЕПЛОЙ: сервер первым — V8 + аддитивная валидация, DTO/топики/интерсептор НЕ тронуты;
  клиентская половина — space-client/CLAUDE.md).** Юзер-решения: кап 20 РАЗНЫХ реакций
  на сообщение (Discord-парити) — 400 на 21-ю различную, toggle стоящих не ограничен.
  **Wire-формат: кастомная реакция едет как `:name:` (в двоеточиях) в ТОМ ЖЕ строковом
  поле `reaction`/`ReactionSummary.reaction`** — дискриминатор структурный (встроенные
  слаги двоеточий не содержат), `heart` и `:heart:` — РАЗНЫЕ реакции; резолвит клиент по
  живому каталогу (модель фазы 1 — удаление эмодзи деградирует чип в текст БЕЗ пушей).
  **Gate-split (load-bearing):** СИНТАКСИС (whitelist ИЛИ `^:[a-z0-9_-]{1,32}:$`) — в
  `resolveReactionTarget` (порядок гейтов сохранён: 403 раньше 400); СУЩЕСТВОВАНИЕ
  (`emojiRepository.existsByName` → 400 "Unknown reaction") — ТОЛЬКО в `addReaction`:
  remove обязан снимать dangling-строку после удаления эмодзи из каталога, иначе чип
  зависал бы навсегда. MessageService += `CUSTOM_REACTION_SLUG`/`customEmojiNameOf` +
  `MAX_DISTINCT_REACTIONS=20` (зеркало идиомы MAX_PINS_PER_CHANNEL) + ctor-dep
  EmojiRepository; кап проверяется внутри ветки `!exists` (идемпотентный повтор
  стоящей реакции short-circuit'ит ДО счётчика) через НОВЫЙ
  `MessageReactionRepository.countDistinctReactionByMessageId` — ЯВНЫЙ JPQL
  `count(distinct r.reaction)`: derived `countDistinct…` считает сущности (= строки);
  TOCTOU двух конкурентных 21-х принят (дешёвого DB-констрейнта «≤N различных» нет).
  **V8__widen_reaction_slug.sql**: `message_reactions.reaction varchar(32)→(64)` (имя
  ≤32 + 2 двоеточия = 34; typmod-widen в PG метадатный — unique-индекс не трогает;
  H2-тесты схему регенерят из entity, entity `@Column(length=64)` + javadoc про оба
  формата). Тесты: ReactionIntegrationTest += 6 (round-trip `:cat:` в
  снапшоте/истории/DELETE — MockMvc НЕ декодирует %3A в path-variable (нет контейнера),
  потому токен в тесте СЫРОЙ, wire-кодирование закреплено клиентским rest-тестом;
  unknown `:ghost:` → 400; `heart`+`:heart:` независимы; кап: 10 встроенных + 10
  кастомных стоят → 21-я различная 400, повтор стоящей 200; remove ПОСЛЕ удаления
  эмодзи из каталога → 200; `::`/`:UPPER:`/33-симв → 400) + MessageServiceTest += 4
  юнита (+`@Mock EmojiRepository`, unused-mock легален под STRICT_STUBS — существующие
  built-in-юниты не тронуты: countDistinct на моке дефолтит в 0) — **365 тестов (было
  355)**.
- [x] **Инвайт-регистрация: регистрация на непустом инстансе ТОЛЬКО по одноразовым
  пригласительным ссылкам + секция Invites (2026-09-30, ДЕПЛОЙ ПАРНЫЙ со space-client;
  смоук pending).** Юзер-решения: открытая регистрация ЗАКРЫВАЕТСЯ — непустой инстанс
  требует валидный секрет, пустой по-прежнему регистрирует ПЕРВОГО юзера (OWNER) без
  секрета (bootstrap-путь живёт до client provisioning — backlog); TTL РЕТРОАКТИВЕН
  (смена пересчитывает `expires_at = created_at + TTL` всех активных; при создании
  `expires_at = created_at + TTL`); список — ТОЛЬКО активные (used/expired/revoked
  строки остаются в таблице как аудит одноразовых секретов, чистка не нужна — одна
  строка на регистрацию). **Миграция `V9__invites.sql`** (таблица invites с
  ОБЯЗАТЕЛЬНЫМ identity-блоком + `server_profile.invite_ttl_hours int default 24`).
  **Сервер:** `Invite` entity (secret plaintext — Copy в UI должен пересобирать ссылку,
  в отличие от хешируемых refresh; `isActive(now)`), `InviteRepository` (`findActive`
  join fetch createdBy; **`claim` — атомарный одноразовый захват**: `@Modifying
  UPDATE ... WHERE secret=? AND revoked=false AND used=false AND expiresAt>now`,
  clearAutomatically/flushAutomatically — грабля stale persistence context; 0 строк =
  проигрыш гонки), `InviteController` (DiscussionController-идиома: НЕТ классового
  @RequestMapping, полные пути на методах — 5 эндпоинтов: GET/POST `/api/invites`,
  DELETE `/api/invites/{id}` (404 только для несуществующего id; существующей
  неактивной — идемпотентный 204), POST `/api/invites/expire-all`, **PUT
  `/api/server/invite-settings`** — живёт ЗДЕСЬ, не в ServerProfileController: TTL —
  домен Invites с гейтом MANAGE_MEMBERS, а не MANAGE_SERVER; `ServerProfileResponse`
  не тронут вовсе). Гейт всех мутаций+чтения — `requireServer(MANAGE_MEMBERS)` (юзер-
  решение «кто может добавлять членов — тот раздаёт ссылки»). Секрет: 24 байта
  SecureRandom → base64url 32 символа (newRawToken-идиома), DIVE-реген ×1. **Link
  собирает сервер**: `http://<host>:<port>/#invite=<secret>`; host/port из advertised
  `ServerProfile.connectionHost/Port`, при незаданных — Host-заголовок запроса (за
  reverse-proxy даст прокси-имя — правильный фикс заполнить advertised через PATCH
  /api/server/connection); схема ВСЕГДА http (профиля схемы нет — TLS = будущий
  provisioning). `InviteResponse` несёт secretPreview (8 симв + «…», полный секрет на
  провод НЕ отдаём — Copy копирует link; все поля non-null, грабля jsonPath-null) +
  `InvitesStatusResponse{ttlHours, invites}` одним GET. Ретро-TTL — load+loop
  (`expiresAt = createdAt.plus(ttl)`, НЕ JPQL Duration-арифметика — диалект-риск на
  H2). WS-пинг bare-Long на `/topic/invites` после каждой мутации (клиент в v1 не
  подписан — задел; топик вне regex ChannelSubscriptionInterceptor). **Register
  (`AuthService.register`, порядок операций = сердце атомарности):** предчек username
  409 → nickname (blank→username; безусловный предчек `existsByNickname` 409
  «Nickname is already taken» — чинит латентную граблю: username, совпавший с ЧУЖИМ
  ником, умирал на UNIQUE(nickname) DIVE с ложным «Username already exists») →
  firstUser=count==0 → `!firstUser && секрета нет` → 403 → findBySecret-предчек
  (человекочитаемая причина) → save юзера (DIVE→409, секрет ещё не расходован) →
  firstUser ? grantOwner (секрет игнорируется) : `claim(...)==0` → 403, ResponseStatus-
  Exception откатывает tx ВМЕСТЕ с INSERT юзера (claim ПОСЛЕ save — нужен id для
  used_by; DIVE-гонки умирают до claim). `RegisterRequest` += 3-м/4-м полями (wire
  append-only): `nickname` (`[\p{L}\p{N}_-]*` — грамматика RenameMemberRequest),
  `inviteSecret`. **Тесты:** НОВЫЙ `InviteIntegrationTest` (16: bootstrap-201; 403 без
  секрета; happy-ник; одноразовость; expired/revoked/unknown 403; плебей 5×403; формат
  link (сидер даёт advertised localhost:8080 — link предсказуем); Host-fallback;
  только активные; expire-all; TTL штамп+ретро; 0/721/null 400; ник-конфликт 409;
  username==чужой-ник → честный 409) + `TestInvites` (тестовый @Component в
  oak.server-пакете test-дерева — поднимается во ВСЕХ @SpringBootTest; mint инвайта
  репозиторием + `registrationBody(username,password)`: пустой сервер → 2-поле тело
  (OWNER-bootstrap, семантика «первый юзер теста = OWNER» сохранена для 403-кейсов),
  непустой → +inviteSecret) — правка хелпера `registerAndLogin` в 22 тестовых файлах
  (30 замен) + `AuthFlowIntegrationTest.register_duplicateUsername` (дубль теперь
  обязан нести валидный секрет — иначе 403 раньше 409). **Живой старт: V9 применена,
  validate чист** (миграция исполняется до web-старта; фоновый прогон упал только на
  «Port 8080 already in use» — на порту живёт старый билд, для смоука обязателен
  рестарт). — **381 тестов (было 365)**. Клиентская половина — space-client/CLAUDE.md.
  **Раунд 2 (тот же день, смоук-репорты юзера; ДЕПЛОЙ ПАРНЫЙ):** (1) **TTL в МИНУТАХ**
  (`V10__invite_ttl_minutes.sql`: `UPDATE … * 60` ДО rename — строки сохраняют спан;
  `invite_ttl_minutes int default 1440`; wire `ttlMinutes` везде, `@Min(1)@Max(525600)`
  = год; юзер-решение: поле задаётся в минутах, клиент рендерит живой перевод словами).
  (2) **Пинги только AFTER COMMIT** (`publishPingAfterCommit`, public static — зовётся и
  из `AuthService`): пинг внутри tx позволял подписчикам рефетчнуть ДО видимости мутации
  (снапшот читал старое состояние — расход ссылки «не исчезал до перезахода», юзер-репорт);
  `AuthService.register` после успешного claim пингует `/topic/invites` — списки админов
  теряют потраченную ссылку live. Тесты: InviteIntegrationTest переведён на ttlMinutes
  (плебей-PUT обязан нести ВАЛИДНОЕ тело — @Valid-400 срабатывает РАНЬШЕ гейта 403).
  **Раунд 3 (юзер-решения, 2026-10-01):** (а) **использованные инвайты удаляются
  НЕМЕДЛИТЕННО** — claim стал атомарным guarded **DELETE** (`InviteRepository.claim`
  возвращает число удалённых строк; 0 = гонка проиграна → 403, rollback откатывает
  INSERT юзера); повтор потраченной ссылки читается как «not valid». Колонки
  used/used_by/used_at стали не нужны — `V11__drop_invite_used.sql` дропает их,
  сущность/запросы упрощены (`isActive = !revoked && expiresAt>now`; findActive/
  revokeAllActive без used-условий; revokeAllActive получил now-параметр).
  (б) **`InvitesCleanup`** (`@Scheduled fixedDelayString oak.invites.cleanup-interval:PT6H`,
  калька RefreshTokenCleanup вкл. @Transactional-на-scheduled-методе — self-invocation-
  правило): сносит revoked/expired-строки (`deleteInactive` — никогда не станут
  активными); потраченных к моменту чистки уже не существует. Тест:
  InvitesCleanupTest (scheduled-вход без @Transactional; revoked+expired+active →
  выживают только активные). — **382 тестов**.
- [x] **Тройной раунд (2026-10-01, серверная половина; клиентская — space-client/CLAUDE.md;
  ДЕПЛОЙ ПАРНЫЙ — сервер первым).** **(A) sizeBytes:** `AttachmentRef` += 7-е `@PositiveOrZero
  Long sizeBytes` + `AttachmentResponse` += 9-е (зеркало): размер знает только ОТПРАВИТЕЛЬ
  (File.size — сервер байты не терминирует; модель доверия width/height), nullable навсегда
  (легаси-строки JSON); единственная точка сборки `toResponse` дописала аргумент; intra-forward
  копирует JSON verbatim → поле едет бесплатно; миграций НЕТ (JSON-колонка). Convenience-
  конструкторы AttachmentRef/CreateChannelRequest делегируют null. **(B) Категории каналов
  (Discord-секции) + sidebar-порядок:** `V12__channel_categories.sql` — CHECK += 'CATEGORY'
  (DROP+ADD обязателен, V4-урок), `parent_id` (self-FK nullable) + `position int not null
  default 0` + backfill `position = id` + индекс. `ChannelType` += CATEGORY (в конец);
  `Channel` += голый `Long parentId` (БЕЗ @ManyToOne self — wire-Long, discussions-
  дисциплина) + `int position`. `ChannelResponse` += 6-е/7-е `Long parentId, int position`
  (wire-арность!); `CreateChannelRequest` += 3-е `Long parentId`. Репо +=
  `findByTypeNotInOrderByPositionAscIdAsc` / `findTopByOrderByPositionDesc` /
  `existsByParentId`. `ChannelService`: list() — порядок (position,id), CATEGORY всегда
  `locked=false` (иначе lockedFor даёт ложный замок !READ); create() — валидации parent
  (CATEGORY-с-parent 400 «A category cannot have a parent» / unknown 400 / не-CATEGORY
  400) + `position = findTop...+1` (Java-дефолт 0 НЕ должен утекать — новый канал прыгал
  бы в начало); delete() — ПЕРВОЙ проверкой `CATEGORY && existsByParentId` → 400 «Category
  is not empty — move or delete its channels first» (до kickAll); rename категории —
  разрешён, ответ unlocked. НОВОЕ `PUT /api/channels/order` (`ReorderChannelsRequest`
  {entries: [{id, parentId|null}]}) — ПОЛНЫЙ список всех не-DM каналов+категорий ровно
  по одному разу (частичный/дубль/чужой id → 400 «Reorder must list every channel and
  category»); позиции перенумеровываются ПЛОТНО по индексу, parentId применяется в той же
  tx; пинг `/topic/channels.changed` **AFTER COMMIT** (приватный `pingChannelsChangedAfterCommit`
  — invites-урок: пинг до коммита = рефеч-гонка). ReadStateService: snapshot/seed skip
  CATEGORY. VoiceMembershipHandler.join: не-VOICE channelId — тихий no-op. Тесты:
  ChannelCategoryIntegrationTest (4: создание+порядок; 3×400-валидации; delete непустой
  400+текст / rename / delete пустого; reorder 403-плебей/неполный-400/CATEGORY-child-400/
  happy порядок+parent) — юзеры уникальны per-класс + @Transactional (иначе 409 на
  повторной регистрации). **(C) /app/voice.invite:** `dto/VoiceInvite(channelId, channelName,
  from, to)` + `websocket/VoiceInviteHandler` (CallSignalingHandler-калька, @Controller):
  сервер штампует from и САМ резолвит channelName (стора получателя может не быть);
  не-VOICE канал / отправитель без VIEW_CHANNEL / blank → ТИХИЙ no-op (call.signal-
  дисциплина, перебор ничего не узнает); доставка `/user/queue/voice.invite` (вне
  интерсептора). Дружбу/присутствие сервер НЕ проверяет — офлайн-получатель = доставка
  в никуда (юзер-решение: живая карточка без persist). Тест VoiceInviteHandlerTest (4).
  — **392 теста**. Смоук: V12 на живой базе + категории/drag/invite по сценарию клиента.
- [x] **Тройной раунд 2: удаление участника + передача овнера + AFK-канал (2026-10-01,
  код готов, смоук pending; ДЕПЛОЙ ПАРНЫЙ со space-client — сервер первым: V13/V14/V15
  + /kick→/remove + аддитивные ownership/afk эндпоинты).** **(A) «Remove from server»
  (переделанный кик, навсегда):** `V13__user_removed.sql` (`users.removed boolean NOT NULL
  DEFAULT false`, banned-калька). Гейты зеркалом banned в 4 местах: `PrincipalUser` +=
  removed-поле (+ JpaUserDetailsService), `JwtAuthenticationFilter` 401 "removed",
  `AuthService.login` 403 "This account was removed from the server" (ДО banned-ветки) +
  refresh REMOVED-ветка (revoke family + 401, noRollbackFor как обычно), `AuthChannelInterceptor`
  bannedOrRemoved → reject CONNECT. `MemberService.remove(userId, actor)`: гейт
  **KICK_MEMBERS** (то же админ-поле, что у старого кика) + иерархия + овнер-таргет 403;
  ПОРЯДОК: push `/user/queue/member.kick {by, banned:false, removed:true}` и голосовой
  eviction СНАЧАЛА (живые сессии знают СТАРЫЙ username), потом **анонимизация** —
  username → `deleted_<id>` (retry-суффикс `-2`, `-3`, … при коллизии), nickname →
  `Deleted-User-<id>` (грамматика ника без пробелов), save, `publish("removed", id)` +
  `/topic/users` push (репaint ростера). НИК ОСВОБОЖДАЕТСЯ (register под старым
  username/nickname — 201), подмены авторства НЕТ по построению: Message.author — live
  FK, wire authorUsername становится tombstone-логином; регистрант под старым ником =
  другой id. `list()` фильтрует removed; `banned()` = banned ∪ removed (аудит; клиент
  различает по MemberResponse.removed — 8-е поле); ban/unban удалённого → 400.
  `VoiceAccessEnforcer.evictEverywhere(username, actor)` — union обоих реестров →
  forceLeave по каждой комнате (occupiedChannels-рефактор, разделяемый с
  enforceConnectForUser). Endpoint: `POST /api/members/{id}/remove` (старый /kick УДАЛЁН
  — парный деплой). Тесты: MemberRemovalIntegrationTest (5: анонимизация+освобождение
  имён+авторство-tombstone; смерть токенов 401/refresh/логин; гейты+овнер+404;
  ban/unban-отказы; tombstone-коллизия-суффикс) + MemberServiceTest (2) +
  evictEverywhere (2) + CONNECT-удалённого (1). **(B) Передача овнерства (pending
  accept/decline):** `V14__ownership_transfers.sql` (identity-блок V9-рецепт ОБЯЗАТЕЛЕН);
  `OwnershipTransfer` (Relationship-идиома: from/to @ManyToOne LAZY, status STRING-enum,
  @CreationTimestamp) + `OwnershipTransferStatus` PENDING/ACCEPTED/DECLINED/CANCELLED +
  `findFirstByStatusOrderByCreatedAtAsc` (одна PENDING на инстанс). DTO:
  OwnershipTransferResponse (id+оба участника id/login/displayName+status+createdAt),
  PendingOwnershipResponse (`{pending:null}` — всегда-JSON), OfferOwnershipRequest,
  OwnershipEvent (OFFERED/ACCEPTED/DECLINED/CANCELLED). `OwnershipTransferService`:
  offer (гейт isOwner 403 / self 400 / banned|removed 400; новая заявка ТИХО отменяет
  существующую; push обоим), accept (403 не-адресат / 409 не-PENDING / **409 «The sender
  is no longer the owner»**; снять OWNER с from → выдать to (findByKind(OWNER)); оба
  MemberEvent rolesChanged → клиенты рефечат members+permissions, короны flip live;
  push обоим), decline/cancel (противоположные стороны, 204), pending (party-scoped
  snapshot), `cancelPendingFor(User)` — из `MemberService.remove()` и `ban()` в их tx
  (заявка умирает с участником; уведомляется противоположная сторона). Controller:
  GET /api/ownership/pending, POST /offer (201) /{id}/accept /decline /cancel.
  **Смоук-фикс 1 (живой репорт «адресату ничего не пришло»): OWNERSHIP_QUEUE =
  "/queue/ownership" БЕЗ /user-префикса** — convertAndSendToUser добавляет его сам
  (конвенция /queue/member.kick и /queue/relationships; с префиксом пуш уходил в
  /user/user/queue/ownership мимо клиентской подписки).
  Тесты: OwnershipTransferIntegrationTest (5) + OwnershipTransferServiceTest (2, InOrder
  свапа+пуши). **(C) AFK-канал:** `V15__voice_afk.sql` (`server_profile.afk_channel_id
  bigint` NULL=выкл + `afk_timeout_minutes int DEFAULT 30`; БЕЗ FK — сброс при удалении
  канала в `ChannelService.delete` кодом, ДО kickAll). `AfkController` (InviteController-
  идиома): GET открыт всем, PUT под **MANAGE_CHANNELS** (фича канальная; 404 unknown /
  400 не-VOICE / @Min(5)@Max(10080)); `public static timeoutOf` — null→дефолт 30
  (inviteTtl-паттерн). Активность: `VoiceActivityRegistry` (ConcurrentHashMap username→
  Instant; touch/lastActive, дефолт EPOCH — читается только для сидящих в комнатах,
  протухшие записи безвредны), `VoiceActivityHandler` `@MessageMapping("/voice.activity")`
  (тихий no-op вне комнат; @Controller-грабля), `VoiceMembershipHandler.join` тайтчит.
  Move: `VoiceMembershipTracker.move(from, to, username)` — sessionsOf-скан (выделен из
  kick) → enter каждой сессии (LEFT старой + JOINED новой на границах 0↔1, ре-сиид меток).
  Свип: `VoiceAfkSweeper` `@Scheduled PT30S` + **@Transactional на scheduled-методе**
  (self-invocation-правило): profile.singleton() → выключено/канал не VOICE → выход;
  по каждому (канал ≠ AFK, username) с `lastActive < now−timeout` → moveToAfk: нет
  CONNECT на AFK-канале → `forceLeave`; иначе `tracker.move` → `voiceAccess.forceKick`
  (порвать старый Janus; клиент перезапустит /room — свежий токен, revokeTokens не нужен)
  → push `VoiceControlEvent("MOVED", afkChannelId, null)` (byUsername null — wire
  расширен, парный деплой). Тесты: VoiceActivityHandlerTest (4), tracker move (2),
  **Смоук-фиксы раунда 3 (2026-10-02):** (а) **овнер не смещался** — accept пушил
  MemberEvent/OwnershipEvent ВНУТРИ tx → рефечи читали докоммитное (invites-грабля):
  ВСЕ пуши OwnershipTransferService (offer/accept/decline/cancel/cancelPending)
  переведены в приватный `pushAfterCommit(Runnable)` (TransactionSynchronization
  afterCommit). (б) **AFK-мьют не действовал** — sweeper шлёт mod-mute-стейт ДО
  MOVED-пуша, а клиент на MOVED перезаходит комнатой (joinVoice, чей локальный
  reset-меток затирал только что пришедший мьют): applyOnEnter перенесён ПОСЛЕ
  MOVED-пуша (TCP-порядок одного сокета) + клиентский enteringAfkRoom-гейт (см.
  space-client/CLAUDE.md). Тесты: sweeper InOrder обновлён.
  **Смоук-фикс раунда 4 (2026-10-02): снятие AFK-designation оставляло мод-мьют
  навсегда** (юзер-репорт: убрал тоггл — канал всё равно сервер-мьютит): метки
  AfkVoicePolicy живут в трекере пер-канально и РЕ-СИИДЯТСЯ при каждом заходе —
  designation уходила, метка оставалась. Лекарь: `AfkController.update` при смене/
  снятии designation со старого канала зовёт НОВЫЙ `VoiceMembershipTracker.
  clearModeratorMutes(channelId)` (снять mod-mute КАЖДОМУ меченому канала, ВКЛЮЧАЯ
  несидящих — их пережившую метку ре-сиид воскрешал бы вечно; self-метки и implied
  не трогаются) + `moderateAudio(unmute)` сидящим; трекер-тест (последний стейт по
  username: self выживает, mod снят, включая ушедшего) + интеграция (PUT null
  размьючивает staged-участника; @AfterEach-выход из трекера — синглтон-грабля).
  **Ретро-мьют (живой репорт того же раунда: юзер сидел в комнате, её назначили AFK —
  мьют не наступил):** AfkVoicePolicy работал только на ВХОДЕ; designation, назначенная
  на занятую комнату, теперь подавляет ВСЕХ уже сидящих (`AfkController.update` при
  landing-designation зовёт applyOnEnter по каждому сидящему — moderateAudio тут же
  гейтит живых издателей forced-слоем); интеграционный тест occupied-room. —
  **431 тест**.
  **Раунд-2 переделка AFK (юзер-решения, 2026-10-02, после проверки Discord-фактов
  поиском — В ДИСКОРДЕ AFK-канал: server-mute НЕ снимается юзером, запрет стрима,
  МОЖЕТ жить в категории):** (1) **`ChannelResponse` += 8-е `boolean afk`** — флаг
  сервера (afkChannelId == channel.id) в `list()`/`rename()`; PUT /api/server/afk
  пингует `/topic/channels.changed` **AFTER COMMIT** (приватный twin
  pingChannelsChangedAfterCommit в AfkController) — все зрители рефетчат бейджи.
  (2) **Канальный супресс: НОВЫЙ `websocket/AfkVoicePolicy`** — КАЖДЫЙ вошедший в
  AFK-канал (свипом или руками) получает МОДЕРАТОРСКИЙ мьют (`tracker.setMuted(true)`
  + best-effort `moderateAudio`): self-unmute его не снимает, только модератор
  (MUTE_MEMBERS) или выход из канала (метка пер-канальная, в других комнатах не
  действует; повторный заход в AFK ре-сиидится трекером). Вызовы:
  VoiceMembershipHandler.join (после enter) и VoiceAfkSweeper.moveToAfk (после
  tracker.move — ДО forceKick). Forced-слой на входе no-op (издателя ещё нет) —
  комплаенс честного клиента едет на voice.state-пуше setMuted; роgue-репаблиш в AFK
  не гейтится (кооперативная модель, как voice-mute до итерации 4). Тесты: НОВЫЙ
  AfkVoicePolicyTest (3) + sweeper InOrder с policy + интеграция afk-флага в списке. —
  **428 тестов**.
- [x] **СМОК-ФИКС: DISCUSSION-каналы утекали в DM-список (2026-09-24, юзер-репорт «фантомные
  DM с самим собой»; клиентская половина саги — space-client/CLAUDE.md).** `DmService.listMine`
  брал ВСЕ каналы участника (`findChannelIdsByUserId`) без фильтра типа — а трекинг обсуждений
  живёт в тех же `channel_participants`, поэтому каждый отслеживаемый тред приезжал в
  `GET /api/channels/dm`; у треда единственный участник-строка = сам трекер, и клиент,
  не найдя пира, рендерил строку с СОБСТВЕННЫМ ником («4 разных разговора с admin23» = 1
  настоящий DM + 3 трека). Фикс: гейт `type ∈ {DM, GROUP_DM}` в цикле listMine. Регрессионный
  тест `DiscussionIntegrationTest.aTrackedDiscussionDoesNotLeakIntoTheDmList` (тред создан и
  автотрекнут — в DM-листе ровно один настоящий DM). 319 тестов. Wire не менялся; деплой
  сервер-only (клиент излечивается первым же рефетчем social).
- [x] **Раунд пермишшенов: запрет ЛС по роли + ревизия каталога + ретайр битов (2026-10-05,
  код готов, смоук pending; ДЕПЛОЙ ПАРНЫЙ со space-client — сервер первым; миграция V16
  трогает только данные, схема не меняется).** **(A) Полная ревизия каталога:** 30 бит,
  реализовано 22; не проверяются нигде 6 (после ретайра): `SPEAK`, `STREAM`,
  `VIEW_AUDIT_LOG`, `USE_VAD`, `PRIORITY_SPEAKER`, `REQUEST_TO_SPEAK` — все в backlog
  (мини-планы с оценками — в плане раунда goofy-leaping-sutton.md; USE_VAD/REQUEST_TO_SPEAK
  признаны неприменимыми до PTT/stage-каналов). Протухшие javadoc MANAGE_EMOJIS/ADD_REACTIONS
  («future») актуализированы. **(B) Ретайр двух битов (юзер-решение):** EMBED_LINKS
  (ordinal 14) и USE_EXTERNAL_EMOJIS (ordinal 17) → константы `RESERVED_EMBED_LINKS`/
  `RESERVED_USE_EXTERNAL_EMOJIS` НА МЕСТЕ (удаление сдвинуло бы ordinal всех последующих
  битов и молча сломало бы сохранённые маски) + поле `reserved` в конструкторе.
  Фильтрация: `PermissionController` (каталог без reserved-строк), `Permissions.names`
  (wire-имена без reserved — OWNER-маска ALL «несла» бы их всегда). `RoleSeeder.
  EVERYONE_DEFAULTS` без EMBED_LINKS. **`V16__retire_reserved_permission_bits.sql`**
  чистит биты 16384|131072 из `roles.permissions` и `allow/deny` обоих видов оверрайдов —
  чтобы будущее переиспользование reserved-бита не унаследовало станый грант (EMBED_LINKS
  сидился в @everyone-дефолты → бит стоит в каждой живой базе; протокол переиспользования —
  в javadoc enum: переименовать константу можно, старые бэкапы до V16 требуют повторной
  очистки). Тест `RbacIntegrationTest.retiredPermissionBitsNeverSurfaceOnTheWire`
  (каталог/MyPermissions/@everyone-дефолты чисты). **(C) RESTRICT_PRIVATE_MESSAGES
  (ordinal 30, GENERAL) — единственный «вычитающий» пермишшен:** роль с битом отбирает
  ЛС у держателя (юзер-решения: отправка И получение; 1:1 с запрещённым заморожен для
  ОБЕИХ сторон — сообщение к нему и есть «получение»; группа — запрещённый молчит сам,
  группа живёт, его нельзя добавить; дружба и звонки 1:1 не тронуты; существующая
  переписка читаема всеми). **Проверка НИКОГДА через effectiveBase** (OR-модель не
  умеет вычитать, а short-circuit ALL сделал бы «restricted» каждого админа):
  `AuthorizationService.dmRestricted(User)` читает СЫРЫЕ маски кастомных ролей
  (ROLE-kind скипается — его ALL «несёт» каждый бит без значения); @everyone-копия бита
  игнорируется (один чекбокс не должен гасить ЛС всему инстансу); OWNER и держатели
  ADMINISTRATOR иммунны (привилегированная модель; чтобы ограничить админа — сначала
  снять админ-бит). `requireDmSendAllowed(author, channel)` — гейт отправки: DM проверяет
  обоих участников, GROUP_DM только автора, прочие типы no-op. Гейты: `DmService.
  getOrCreateDm` (обе стороны), `createGroupDm`/`resolveOthers` (создатель + каждый
  участник), `addParticipant` (актор и добавляемый), `MessageService.create` +
  `forward` на TARGET-канале (форвард = новое DM-сообщение). НЕ тронуты (читаемость):
  `listMine`, `listByChannel`, `ReadStateService.*`, `ChannelSubscriptionInterceptor`,
  `requireCanModify` (edit/delete своих старых сообщений живут), реакции/пины в DM,
  `RelationshipService`, `CallSignalingHandler`. **Wire (append-only, парный деплой):**
  `MyPermissions` += 3-е `boolean dmRestricted`; из `permissions`-имён бит фильтруется
  (`Permissions.grantNames` — грант-лист для can()-гейтинга клиента; раунд-трип редактора
  ролей остаётся на `names`); `MemberResponse` += 9-е `boolean dmRestricted` (from() +=
  параметр, 4 call-site'а MemberService: list/banned/assignRoles/rename). Сидер: ничего
  (бит вне дефолтов, отсутствие бита = отсутствие запрета, ремонт не нужен). Тесты:
  НОВЫЙ `DmRestrictionIntegrationTest` (7: нельзя создать DM с обеих сторон; заморозка
  существующего 1:1 в обе стороны + forward 403 + history/listMine/ack живут; группа —
  restricted молчит, остальные пишут, его не добавить; OWNER/ADMIN иммунны; бит на
  @everyone никого не ограничивает; снятие роли живо размораживает; wire-флаги без
  утечки имени бита). — **439 тестов (было 431)**.
- [x] **Безопасность раунд 1 из 5: запрет STOMP SEND в брокерские топики (2026-10-05,
  security-аудит; НОВЫЙ `websocket/SendDestinationInterceptor` + регистрация в
  `WebSocketConfig.configureClientInboundChannel` третьим интерсептором).** Корневая
  дыра аудита (CRITICAL): простой брокер НЕ авторизует SEND по назначению — клиентский
  фрейм с destination `/topic/channel.5` или `/user/<victim>/queue/...` раздавался
  подписчикам КАК БУДТО его послал сервер. Это давало любому аутентифицированному
  участнику (stompy против `/ws`): инжект самодельных `MessageResponse` в любые
  каналы/чужие DM (рендер + mention-пинги, не персистится), спуф `call.signal RING` с
  подменённым `from` (сервер штампует from только на ЛЕГИТМНОМ `/app`-пути), forged
  `member.kick`/`voice.control KICKED` в очереди жертв (клиент сам рвёт сессию —
  remote-logout без прав), спуф presence/voice-state, fan-out амплификацию без rate
  limit. Фикс: интерсептор дропает (null + WARN с username) любой SEND, чей destination
  вне `/app/`** — у клиента нет легитимной причины публиковать в брокер (все
  клиент→сервер команды = @MessageMapping под `/app`; `/user/queue/...` пишет только
  сервер через convertAndSendToUser). CONNECT/SUBSCRIBE не тронуты (их ведут
  AuthChannelInterceptor / ChannelSubscriptionInterceptor). Тесты: НОВЫЙ
  `SendDestinationInterceptorTest` (8: /app проходит; /topic, /user/чужая-queue,
  /queue, голый /app, null-destination — дроп; SUBSCRIBE/CONNECT passthrough). —
  **447 тестов (было 439)**. Wire не менялся — деплой только сервер. Контекст
  полного аудита (C1–C2, H1–H9, M1–M8, L) и план раундов 2–5 — в сессии 2026-10-05;
  раунды 2 (секреты деплоя: Janus admin secret/MinIO IAM/TURN auth-secret), 3
  (REST/MinIO ключи+квоты), 4 (Janus pin-сверка/ban-evict/display-сверка), 5
  (получатели call/voice-invite + override/voice-read гейты) — утверждены юзером.
- [x] **Безопасность раунд 2 из 5: секреты деплоя — Janus admin secret / MinIO scoped
  IAM / TURN REST-API креды (2026-10-05, C2+H5+H6; ЖИВАЯ ВЕРИФИКАЦИЯ ПРОЙДЕНА).**
  **(1) env/.env УШЁЛ ИЗ РЕПО:** `.gitignore` += `env/.env` (git rm --cached); шаблон
  `env/.env.example` (change-me-плейсхолдеры + инструкция `openssl rand -hex 32`);
  локальный env/.env перегенерён (секреты только на этой машине; POSTGRES/MINIO_ROOT
  не тронуты — volume привязан к ним). **SSOT для Spring:** `spring.config.import:
  optional:file:./env/.env[.properties]` в application-dev.yml — Spring и compose читают
  ОДИН файл. **ГРАБЛЯ (живая): расширение `.env` Spring МОЛЧА игнорирует** (dotfile без
  распознанного расширения ≠ properties) — обязателен суффикс формата `[.properties]`;
  без него import тихо пропускается и побеждают фолбэки yml (симптом: MinIO
  signature-does-not-match при верных кредах в .env). **(2) JANUS (C2):** janus.jcfg
  `admin_secret = "@JANUS_ADMIN_SECRET@"` ПЛЕЙСХОЛДЕР; Dockerfile ENTRYPOINT sed'ит
  env в конфиг при старте контейнера (ENV-фолбэк для голого docker run = change-me);
  compose передаёт `JANUS_ADMIN_SECRET` из .env. Секрет больше НЕ в образе/репо; PIN
  всех комнат (HMAC от него) стал невычислимым извне. Живая проверка: admin-запрос с
  новым секретом авторизуется, со старым публичным `janus_admin_secret` — 403.
  7088 остаётся host-mapped ТОЛЬКО для dev-топологии (oak-server на хосте; Windows
  port-proxy не даёт отличить источник — ACL бессилен); PROD-NOTE в compose: в реальном
  деплое держать в docker-сети. **(3) MinIO (H5):** НОВЫЙ one-shot сервис `minio-init`
  (image minio/minio:latest — ВНУТРИ него есть /usr/bin/mc, тянуть minio/mc НЕ надо:
  Docker Hub с этой машины недоступен; depends_on healthy) создаёт scoped-юзера
  oak_app + policy `oak-app` (CreateBucket на *, ListBucket/GetBucketLocation на
  бакет, s3:* на objects) — идемпотентен. application-dev.yml: `oak.minio.access-key/
  secret-key` читают `MINIO_APP_USER/MINIO_APP_PASSWORD` (НЕ root: root остался только
  для консоли/минта юзера). Живая проверка: mc с oak_app видит только oak-attachments;
  oak-server стартовал с app-кредами (bucket verify прошёл). **(4) TURN (H6):** coturn
  `--use-auth-secret --static-auth-secret=${TURN_STATIC_AUTH_SECRET}` (минус --user и
  --lt-cred-mech — coturn WARNING'ал о смешении методов, use-auth-secret самодостаточен);
  `TurnProperties` = (host, port, staticAuthSecret, credentialTtl, realm); НОВЫЙ
  `service/TurnCredentialIssuer` — REST-API тикет `<expiry-epoch>:<login>` +
  base64(HMAC-SHA1(secret, username)), TTL 1ч (аллокации переживают экспаери креда);
  CallController минтит FRESH тикет на каждый /api/call/config — wire НЕ менялся
  (username/credential те же поля RTCIceServer), space-client не тронут. Тесты: НОВЫЙ
  `TurnCredentialIssuerTest` (5: RFC-2202-вектор HMAC; username-формат; base64-раундтрип
  20 байт; детерминизм; expiry-продвижение) + CallConfigIntegrationTest переписан
  (regex `<epoch>:alice` вместо статической пары). — **452 теста (было 447)**.
  **ДЕПЛОЙ-ИНСТРУКЦИЯ: docker compose build janus && docker compose up -d → рестарт
  oak-server** (старый процесс с прежним admin-secret получит 403 на add_token — голос
  до рестарта мёртв; это намеренно громко). Прод-границы задокументированы: 7088/9001
  не хостить, TLS-прокси — backlog (provisioning).
- [x] **Безопасность раунд 3 из 5: REST/MinIO ключи и квоты (2026-10-05, H1–H4; НОВЫЙ
  `service/AttachmentSlotRegistry`).** **(H1) Avatar/server-icon confirm-гейт:** каждый
  минт-слот (`/me/avatar[/original]`, `/api/server/avatar[/original]`) регистрируется в
  реестре key→owner (in-memory, TTL 24ч, кап 10k с деградацией к «unknown»); confirm
  принимает ТОЛЬКО ключи правильного namespace (`avatars/` не-оригинал / `avatars/originals/`
  / `server-avatars/`) И выданные ЭТОМУ юзеру — прежде принимался любой ≤512-символьный
  ключ, и последующий replace/remove УДАЛЯЛ чужой объект (все эмодзи/стикеры/чужие аватары
  инстанса — вandalism-примитив любого участника). Рестарт сервера между PUT и confirm
  (секунды) = «upload again» — осознанная цена. NoopAttachmentUrlSigner.newOriginalKey
  приведён к реальному namespace `avatars/originals/noop/`. **(H3a) sanitized()-гейт
  сообщений:** каждый ключ аттача И thumbnailKey обязан начинаться с `attachments/`
  (чужой namespace — 400), ключ, известный как выданный ДРУГОМУ юзеру — 400 (контрабанда
  чужого объекта в вечную переподписку через /link); НЕизвестный ключ проходит (пережил
  рестарт — префикс есть жёсткая граница). sanitized() получил authorUsername-параметр
  (create + forward-режим-b). **(H4) Минт-слотов гейт:** POST /api/attachments теперь
  требует server-scoped ATTACH_FILES (канальные override проверяются в create сообщения,
  где канал известен) + per-user rolling-квота 60 слотов/10мин → 429 (было: любой аккаунт
  мог минтить бесконечно — DoS диска с коллатеральной чисткой ЧУЖИХ сообщений ночным GC).
  Signed content-length НЕ делается: presigned PUT не несёт условий (только POST-policy,
  = клиентские правки) — задокументированная граница. **(H2) ChannelService.delete
  type-guard:** DM/GROUP_DM/DISCUSSION id → 400 «Not a server channel» (калька rename)
  ДО evictions/purge — MANAGE_CHANNELS-держатель больше не может перебором id снести
  чужие личные переписки. Тесты: AttachmentSlotRegistryTest (5: владелец/чужой/unknown,
  TTL, кап окна, скольжение через advanceClockBy-шейм), AttachmentIntegrationTest += 2
  (namespace-гейт вкл. thumbnail; rate-limit 60→429, другой юзер жив), UserAvatarIntegration
  Test += confirmRejectsForeignAndUnmintedKeys, ChannelServiceTest += deleteRefusesDmAnd
  DiscussionIds; фикстуры тестов переведены на attachments/-ключи (att/→attachments/).
  — **461 тест (было 452)**. Wire не менялся — деплой только сервер;客户端 space-client
  НЕ тронут (кросс-инстанс форвард ре-аплоадит через тот же POST — гейт проходит у
  легитимного отправителя с ATTACH_FILES).
- [x] **Безопасность раунд 4 из 5: Janus enforcement (2026-10-05, H7+H8+H9).**
  **(H7) ensureRoom pin-сверка:** существующая комната «наша» только если НАШ pin её
  открывает — verify-join контроль-сессией; plugin-error 433 в ACK или poll → ГРОМКИЙ
  `JanusAdminException("squatted")` + ERROR-лог (RoomController → 503) вместо тихого
  вечного 433 у всех клиентов (room-id = channelId, токены plugin-scoped — любой участник
  мог застолбить id своим pin'ом ДО ленивого создания; destroy чужой комнаты невозможен —
  plugin-secret не наш, поэтому детект+диагностика, не самолечение). Парсер
  `pluginErrorCode` (async videoroom-ошибки в plugindata). Пин-проба без joined-события в
  poll-бюджете = WARN «treat as ours» — НИКОГДА не squatted-вердикт на транспортном
  сбое. **(H8) ban() эвиктит из голоса:** `voiceEnforcer.evictEverywhere` в ban() (калька
  remove()) — REST/STOMP режут мгновенно, но rogue с уже минченым токеном продолжал
  вещать/слушать до таймаута сессии. **(H9) display-спуф-контрмера:** НОВЫЙ
  `kickUnknownPublishers(channelId, knownUsernames)` в JanusVoiceAccessService (Http —
  kick по publisher ID без display-матча; Noop — пусто): каждый паблишер с display,
  не резолвящимся в живого участника комнаты (tracker ∪ tokenHolders) и не «oak-control» =
  ghost → мгновенный kick; НОВЫЙ `VoiceGhostSweeper` (@Scheduled PT60S
  `oak.voice.ghost-sweep-interval`) прогоняет UNION обоих реестров — пустые комнаты ноль
  запросов, обитаемая = один passive-join за тик; мгновенность по-прежнему у модераторских
  действий. Тесты: JanusVoiceAccessServiceTest += 4 (squatted-433; pluginErrorCode;
  kickUnknownPublishers только ghost по id; exists-тест получил poll-стаб),
  VoiceGhostSweeperTest (2: union-множества; idle — никогда), MemberServiceTest +=
  banEvictsTheVictimFromVoiceToo. — **467 тестов (было 461)**. Wire не менялся.
- [x] **Безопасность раунд 5 из 5: получатели личных пушей + read-гейты метаданных
  (2026-10-05, M1–M3; НОВЫЙ `service/DirectContactGate`).** **(M1) Гейт адресата для
  1:1-каналов связи:** `/app/call.signal` и `/app/voice.invite` прежде ретранслировали
  `to` КАК ЕСТЬ — любой участник звонил/приглашал ЛЮБОГО (вкл. заблокировавших его и
  замороженных RESTRICT_PRIVATE_MESSAGES — звонок был обходом запрета ЛС), без лимита.
  НОВЫЙ `DirectContactGate.canReach(sender, target, cardPush)`: rate-окна (все сигналы
  300/10мин — ICE-бёрсты влезают; card-push кадры RING/voice-invite 10/10мин против
  ринг-спама) → не-self, target существует и не banned/removed → нет блока в обе стороны
  (RelationshipRepository.blockedEitherDirection) → ни одна сторона не dmRestricted
  (личная связь ВКЛЮЧАЕТ звонки). Дропы тихие (call.signal-дисциплина). Оба хендлера
  (CallSignalingHandler + VoiceInviteHandler после VIEW-гейта) прогоняют кадры через
  гейт; wire не менялся. **(M2) Override-чтения под MANAGE_CHANNELS:**
  `ChannelOverrideService.list/listUserOverrides` += actorUsername + requireServer
  (контроллерные GET + @AuthenticationPrincipal) — прежде матрицы ролей и СПИСОК
  ЮЗЕРНЕЙМОВ с приватным допуском читались любым по перебору id, ломая конфиденциальность
  скрытых каналов на уровне метаданных. **(M3) `GET /api/voice/members` VIEW-фильтрован:**
  VoiceMembershipController фильтрует snapshot по hasInChannel(VIEW_CHANNEL) (канал
  удалён/неизвестен — строка выкидывается) — occupancy скрытых голосовых больше не
  выдаётся одним GET; RESIDUAL (осознанный): live-дельты `/topic/voice` /
  `/topic/voice.state` остаются broadcast (простой брокер не умеет per-viewer доставку) —
  закрыта pull-сторона, помечено в javadoc контроллера. Тесты: НОВЫЕ
  DirectContactGateTest (6; staged-фикстура с lenient-стабами — негативные пути
  шорт-circuit'ят до поздних проверек, STRICT_STUBS), CallSignalingHandlerTest (4),
  VoiceInviteHandlerTest += gated-кейс (+mock), PrivateChannelIntegrationTest +=
  overrideListsAndVoiceSnapshotStayInvisibleToNonManagers (M2 403/200 + M3 скрытый
  VOICE-канал исчезает из снапшота плебея, живёт у овнера). — **479 тестов (было 467)**.
  Wire не менялся — деплой только сервер.

  **ИТОГ АУДИТА 2026-10-05 (5 раундов, все серверные):** закрыты C1 (STOMP-инжект), C2
  (Janus admin secret), H1–H9 (арбитражные удаления объектов, DM-purge, capability-ключи,
  квоты, MinIO IAM, TURN-тикеты, squoттинг, ban-эвикт, display-спуф), M1–M3 (получатели,
  override-read, voice-read). ОСТАЛОСЬ (по согласованию — распланировать отдельно после
  смоука): M4 SPEAK/STREAM-раунд, M5 link-preview SSRF (клиент), M6 login-брутфорс-лимит,
  M7 safeStorage-токены + server-side logout, M8 AFK-видео/mod-deafen форс, L1–L8
  (bans-лист, LIKE-oracle, CORS, content-type, server-scoped модерация, логины в
  /api/users, AFK-GET, edit-after-revoke, TLS/токен-в-URL). СМОУК (объединённый, после
  рестарта oak-server из IDEA): два клиента — голос/шар/звонок живы (секреты-SSOT),
  бан сидящего, мьют, скрытый голос-канал невидим плебею в сайдбаре, Server Settings →
  Channels у менеджера открывается, аватарка/эмодзи грузятся, аттачи отправляются.
- [x] **Смоук-фикс аудита, раунд 5: DirectContactGate НЕ гейтит RESTRICT_PRIVATE_
  MESSAGES (2026-10-05, живой репорт «у вызываемого нет ни звука, ни тоста»).** По
  серверному логу: ВСЕ кадры звонка (RING+HANGUP, обе стороны) падали в
  `[call.signal] dropped (gated)`; в БД у admin11 висела роль «No-DM» с битом
  RESTRICT_PRIVATE_MESSAGES — остаток смоука пермишшен-раунда. Первая редакция M1-гейта
  считала звонок «личной связью» и гасила его при dmRestricted любой стороны — ВОПРЕКИ
  юзер-решению раунда пермишшенов («дружба и звонки 1:1 — НЕ трогаем»). Фикс:
  dmRestricted-проверка УДАЛЕНА из DirectContactGate (блокировки/banned/removed/rate
  остались); гейт теперь сам INFO-логирует причину каждого отказа
  (`[contact-gate] X → Y: refused (blocked pair | ring rate limit | …)`) — «почему
  тишина» читается прямо с лога. Тест dmRestrictionDoesNotGateCalls переписан на
  verify-never(dmRestricted). Граница задокументирована в javadoc: RESTRICT живёт
  ТОЛЬКО в DmService/MessageService. — 479 тестов. Рестарт oak-server нужен.
- [x] **Безопасность раунд 6 из 7: SPEAK enforcement + STREAM wire (2026-10-05, код готов,
  смоук pending; ДЕПЛОЙ ПАРНЫЙ — ДВА wire-расширения: `VoiceControlState` += 9-е `permMuted`,
  `ChannelMyPermissions` += 3-е `stream`).** (1) `VoiceMembershipTracker` += 7-е множество
  `permMutedByChannel`: self-unmute И модераторский Unmute его НЕ снимают (каждое пишет своё
  множество), leave ЧИСТИТ (право пересчитывается на входе — в отличие от modMuted, переживающего
  ревход), isMuted/hasAnyState/publish = union; wire-флаг permMuted отдельным origin. (2) НОВЫЙ
  `websocket/SpeakVoicePolicy` (калька AfkVoicePolicy): `applyOnEnter(User, Channel)` из
  `VoiceMembershipHandler.join` ПОСЛЕ afk-policy — `!hasInChannel(SPEAK)` → setPermMuted(true) +
  moderateAudio(mute) + `[speak]`-лог; право есть → ничего (метка умерла на leave, поднимать
  нечего). (3) SPEAK-проход в `VoiceAccessEnforcer`: публичные методы ПЕРЕИМЕНОВАНЫ
  `enforceConnect→enforceChannelChange` / `enforceConnectForUser→enforceForMember` (проход теперь
  сверяет ОБА права — старые имена врали); общий приватный `enforceAgainst`: потеря CONNECT →
  forceLeave; потеря SPEAK → permMute+moderate(true); возврат SPEAK при живой метке → lift, где
  moderate идёт по UNION (не открыть рот заглушённому AFK-мьютом); хуки те же (4 мутации
  ChannelOverrideService, RoleService.update/delete, MemberService.assignRoles);
  RoomController.room НЕ гейтит SPEAK (слушать можно). Rogue-репаблиш кооперативен как у AFK:
  мгновенности нет, sweep-модerate достаёт паблишера по display, union tracker+tokenHolders
  покрывает non-STOMP rogue (метка в трекере для него ставится, но НЕ чистится ничем, кроме
  sweep-lift — осознанная мелкая утечка множества, single-instance объёмы). (4) `ChannelMyPermissions`
  += `stream` = hasInChannel(STREAM) — клиентский гейт share-кнопки (форс-слой невозможен: видеомьют
  в Janus недоступен). (5) **STREAM добавлен в `EVERYONE_DEFAULTS` + `ensureEveryoneStream()`**
  (ADD_REACTIONS-прецедент): бит никогда не сидился, живые БД засеяны без него — без ре-гранта
  деплой раунда СКРЫЛ бы шаринг-кнопку всем плебеям; забирать STREAM = per-role/per-channel deny,
  никогда флип базовой строки. Тесты: VoiceMembershipTrackerTest (+2: переживание обоих unmute'ов
  с wire-флагами; смерть на leave), НОВЫЙ SpeakVoicePolicyTest (2), VoiceAccessEnforcerTest
  (переименования + 3 SPEAK-кейса: мьют-вместо-кика, lift-по-union, нетронутый оратор),
  RbacIntegrationTest += channelMyPermissions_streamDenyHidesTheWireFlag (deny → false у плебея,
  true у овнера) + stream-ассерты в send-override. — **487 тестов (было 479)**.
- [x] **Безопасность раунд 7 из 7: хвостовой пакет аудита (M5–M8 + L; юзер-решения:
  mod-deafen → ПОЛНЫЙ kick; logout = `POST /api/auth/logout` + `/logout-all`;
  L6 логины в /api/users — ПРИНЯТЬ как границу).** Фаза A (сервер): (1) M6 —
  `service/LoginRateLimiter` sliding window (идиома DirectContactGate, без
  зависимостей): per-account 20 неудач/10мин + per-IP 60/10мин → 429, успех чистит
  счётчик аккаунта; uniform-ошибка логина сохраняется; (2) M7 — logout отзывает
  предъявленный refresh (идемпотентный 204 всегда), logout-all ревокает семью по
  принципалу; (3) M8-AFK — сидящий в AFK-канале с живым ВИДЕО (пассивный probe
  publishers, механика ghost-sweeper) → forceKick; (4) M8-deafen — mod-deafen →
  forceLeave (полный kick; метка переживает ревход и ре-сиидится — цикл
  «rogue перезаходит → deafen-пуш заново» задокументировать); (5) L1 —
  GET /api/members/bans → BAN_MEMBERS; (6) L2 — attachmentExists через
  `MessageService.likePattern` (escape %/_); (7) L3 — CORS `allowCredentials(false)`
  (wildcard ОСТАЁТСЯ: Electron-рендерер file://-origin ходит в CORS-режиме — убирать
  нельзя; Bearer в header → CSRF-поверхность нулевая). Фаза B (клиент): (8) M5 —
  SSRF-гейт `space-net:fetch` (main): DNS-резолв + блок private/loopback/link-local
  (IPv4+IPv6) ДО запроса, редиректы manual с перепроверкой Location, блок → тихий
  no-preview; чистая `isBlockedAddress` + тесты; (9) M7 — KV-значения `connection:*`
  шифруются `safeStorage` целиком (маркер `enc:v1:`, plaintext-миграция на лету,
  недоступно → как есть + WARN); logout/logout-all вызываются при re-login-замене и
  Delete server record. ПРИНЯТО БЕЗ КОДА (границы модели): L4 content-disposition
  (ломает inline-рендер медиа), L5 server-scoped голос-модерация (Discord-парити),
  L6 логины (решение юзера), L7 AFK-GET + edit-after-revoke, live-дельты /topic/voice,
  TLS (provisioning-backlog). — **Фаза A реализована (2026-10-05):** **M6** — НОВЫЙ
  `service/LoginRateLimiter` (rolling deques, идиома DirectContactGate; проверка
  ОТДЕЛЬНА от записи — заблокированная попытка ничего не дописывает, окно дренится
  по времени): per-account 20 неудач/10мин + per-IP 60/10мин → 429 ДО
  credential-проверки; успех чистит только аккаунтное окно; BadCredentials-ветка
  (неизвестный логин = неверный пароль, uniform-ошибка сохранена) и
  banned/removed-ветки (пароль верен) пишут onFailure; память ограничена — за
  4096 различных ключей сперва выпариваются истёкшие окна, затем карта чистится
  целиком (availability-over-precision для single-tenant). Интеграционный тест
  ходит с DEDICATED remote address (лимитер — контекстный singleton, окна НЕ
  откатываются транзакцией — общий MockMvc-адрес не должен наследовать его
  неудачи). **M7** — `POST /api/auth/logout` (тело RefreshRequest; отзывает
  предъявленный refresh, идемпотентный 204 всегда) и `POST /api/auth/logout-all`
  (по принципалу из Bearer — permitAll-путь, но фильтр всё равно резолвит JWT;
  без принципала 401, не молчаливый 204). Logout НЕ путь reuse-detection:
  уже-ротированный токен просто мёртв, не «кража» — семейный ревок убил бы другие
  устройства; logout-all = findByUser-ревок всех семей (changePassword-стойка
  без re-issue). **M8-AFK** — НОВЫЙ `websocket/VoiceAfkVideoSweeper`
  (`oak.voice.afk-video-sweep-interval`, дефолт PT60S): occupied AFK-комната
  (tracker ∪ tokenHolders, пустая — даром) →
  `JanusVoiceAccessService.kickVideoPublishers` (НОВЫЙ метод: пассивный probe
  publishers, whole-kick каждого ИЗВЕСТНОГО публишера с video-stream; ghost'ы
  остаются ghost-sweeper'у; мутирование только video-m-line невозможно — плагин
  не имеет per-stream video mute). HttpJanus += `videoMidOf` + общий приватный
  `kickPublisher` (три kick-сайта унифицированы); Noop-реализация — no-op.
  **M8-deafen** — `VoiceControlHandler.deafen` (модераторская ветка): метка
  пишется ПЕРВОЙ (переживает kick by design), затем forceLeave — полный
  четырёхшаговый выход; undeafen сохраняет старый union-рецепт moderateAudio.
  ЗАДОКУМЕНТИРОВАННЫЙ ЦИКЛ: rogue перезаходит → марка ре-сиидится join-пушем →
  честный клиент глушит себя сам; rogue сидит deafened только кооперативно до
  следующего действия модератора (re-kick на каждом входе = лок-аут из войса,
  это дело kick'а, не deafen'а). **L1** — `GET /api/members/bans` под
  BAN_MEMBERS (гейт в `MemberService.banned`, как все мутации; клиентские
  Members-секции без бита — тихий пустой аудит). **L2** —
  `MessageService.attachmentExists` через собственный likePattern (escape
  %/_/обратный слэш — ключ пользовательский, wildcard-инъекция в re-mint
  закрыта). **L3** — CORS `allowCredentials(false)` (wildcard-ORIGIN остаётся:
  Electron file://-рендерер; auth в Authorization-header, cookie-сессий нет →
  CSRF-нуль). Тесты: НОВЫЕ `LoginRateLimiterTest` (3) и `VoiceAfkVideoSweeperTest`
  (3); `JanusVoiceAccessServiceTest` (+2: videoMidOf; kickVideoPublishers — ровно
  один kick, известного видео-публишера), `VoiceControlHandlerTest` (+2:
  deafen→forceLeave без moderateAudio; undeafen-рецепт), `MemberServiceTest`
  (+1: bans-гейт doThrow на void requireServer), `AuthFlowIntegrationTest`
  (+3: logout не убивает чужую семью + идемпотентность; logout-all все семьи +
  401 без принципала; brute-force 429 с dedicated-адресом). — **501 тестов
  (было 487)**. Смоук pending. Фаза B (клиент) — space-client/CLAUDE.md.
- [x] **Раунд логгирования (2026-10-06, код готов, смок pending; клиентская половина — space-client/CLAUDE.md; wire-изменений нет, миграций нет).** **(1) Ротация серверного лога:** logback FileAppender(append=false, рестарт стирал прошлый прогон) → RollingFileAppender + SizeAndTimeBasedRollingPolicy: активный файл тот же `~/.oak-server/oak-server.log`, append=true, часовой ролловер `oak-server.%d{yyyy-MM-dd-HH}.%i.log.gz`, maxFileSize 50MB, maxHistory 24 (≈ сутки), totalSizeCap 1GB, cleanHistoryOnStart; test-профиль по-прежнему console-only. **(2) Видимые 4xx/5xx:** GlobalExceptionHandler += @Slf4j — каждый ResponseStatusException WARN-логируется одной строкой `[api] METHOD /uri -> 403 Forbidden: reason` (класс "ResponseStatusException не reaches лога" — дважды виноватили клиент); валидация логируется в @Override handleMethodArgumentNotValid; NEW catch-all Exception.class → 500 {status,error,message} + ERROR со стеком. Advice НАСЛЕДУЕТ ResponseEntityExceptionHandler — иначе широкий Exception-хендлер глотал бы стандартные MVC-статусы (405/406 → 500): свой @ExceptionHandler для MethodArgumentNotValidException при этом ЗАПРЕЩЁН (AmbiguousMappingException на старте — конфликт с родительским списком), только @Override. **(3) Выгрузка:** `GET /api/server/logs` (LogsController, гейт MANAGE_STORAGE — тот же requireManageStorage-хелпер, что вкладка Server) → zip (ServerLogsService): активный лог + ротированные .gz (распакованные в текст, читаются одним кликом, кап 48MB — старшие пропускаются с записью в манифест), containers/<name>.log из НОВОГО ContainerLogCollector, manifest.txt (время/хост/ОС/java/uptime/перечень). ContainerLogCollector хвостит docker-compose-периферию через docker CLI хоста (json-file логи контейнеров снаружи не читаются, Docker Desktop Windows — внутри WSL VM): `docker ps --format {{.Names}}` → матч имени == или суффикс `-{name}-N` (compose-имена env-postgres-1) → `docker logs --timestamps --tail N`; конфиг oak.logging.container-logs.{enabled(true)/containers(postgres,minio,janus,coturn)/tail-lines(2000)/timeout(PT10S)}; тестируемый шов CommandRunner, реал — ProcessBuilder с redirectError(DISCARD) + killer-нить на таймауте (полный stdout-пайп без читателя дедлочит waitFor); ЛЮБОЙ сбой (нет docker/контейнера/timeout) → graceful "unavailable: …" в архив, не ошибка загрузки. Директория логов переопределяется oak.logging.directory (используют тесты). Тесты: NEW ContainerLogCollectorTest (5), NEW LogsIntegrationTest (401/403/200 + PK-magic + manifest; тест указывает oak.logging.directory=target/test-logs и гасит контейнеры — машина разработчика имеет живые ~/.oak-server и Docker). — **509 тестов (было 501)**. **Follow-up (юзер-решение 2026-10-06, советы по логу): SQL-лог Hibernate ВЫКЛЮЧЕН** — `spring.jpa.show-sql: false` в application-dev.yml: голый show-sql (без bind-параметров и таймингов) почти ничего не диагностирует, но доминирует по объёму и ВЫТЕСНЯЕТ полезные строки [api]/[auth-refresh]/[storage-gc] из фиксированного суточного retention-окна; включать ТОЛЬКО точечно на время N+1-охоты. ЗАПРЕЩЁН `org.hibernate.orm.jdbc.bind: TRACE` — bind-лог печатает ЗНАЧЕНИЯ, а в БД лежат plaintext invite-секреты и хеши refresh-токенов (утечка в файл/выгрузку). Правильный инструмент БД-диагностики — slow-query лог Postgres: `command: postgres -c log_min_duration_statement=200ms -c log_parameter_max_length=0` в env/docker-compose.yml (длительность + ФОРМА запроса, едет в docker logs → containers/postgres.log выгрузки серверных логов). **Смоук-итерация (2026-10-07): audit-sensitive нашёл дыру — bind-ЗНАЧЕНИЯ slow-query (тексты ЛС, invite-секреты, хеши refresh) печатались бы при дефолтном log_parameter_max_length=-1; добавлен `-c log_parameter_max_length=0` (PG16-дока: 0 = bind-параметры НЕ логируются в не-error записях; JDBC/Hibernate ходит extended-протоколом Parse $1+Bind — флаг глушит ровно эти значения, SQL-форма остаётся). Грабля теста: psql `EXECUTE q('литерал')` инлайнит значение в ТЕКСТ команды — маскировку bind-параметров так не проверить. Аудит остального: сервер/клиент/janus/minio — чисто (Credentials=[PROTECTED], nativeHeaders не логируются, payload только byte[N]). Имя выгрузки переименовано в «server-logs-<stamp>.zip» (юзер-решение, единообразно с client-logs).** **Локаль валидации зафиксирована англ. (смоук-репорт 2026-10-07: «размер должен быть в диапазоне…» в теле 400, [api]-логе и клиентском тосте): НОВЫЙ `config/FixedEnglishLocaleConfig` — FixedLocaleResolver(ENGLISH) (бин ОБЯЗАН зваться `localeResolver` — имя, которое ищет DispatcherServlet); Hibernate Validator интерполирует дефолтные сообщения по локали ЗАПРОСА (Accept-Language) — регрессионный тест ValidationLocaleIntegrationTest шлёт Accept-Language: ru + @Size-нарушение и требует англ. stock-текст. Тесты: 510 (было 509).**
- [x] **Healthcheck периферии (2026-10-07, код готов, смоук pending; клиентская половина — space-client/CLAUDE.md; ДЕПЛОЙ: сервер первым — аддитивный `GET /api/server/health`, старый клиент эндпоинт не зовёт; миграций нет).** Статус четырёх интеграций (postgres/minio/janus/coturn) админу в Server Settings → Server. НОВЫЕ `ServerHealthService` + `ServerHealthController` (гейт MANAGE_STORAGE, requireManageStorage-хелпер StorageController) + DTO `ServerHealthResponse{checkedAt, services[{name, service{status,latencyMillis,detail}, container{status,health,detail}}]}` — **две грани на сервис**: (1) **Service-проба** — oak-server проверяет РАБОТОСПОСОБНОСТЬ интеграции своими же клиентами/адресами: postgres `DataSource.getConnection()+isValid(2)`; minio `bucketExists` (ensureBucket-прецедент); janus `JanusAdminClient.listTokens()` с разбором janus-вердикта (transport-фейл И janus-"error" оба DOWN, второй несёт reason — гейтвей жив, но админ-связь сломана = голос мёртв); coturn TCP-connect `TurnProperties.host():port` (таймаут 2 c). Пробы ПАРАЛЛЕЛЬНО на daemon-пуле `oak-health` (4 нити) с бюджетом `future.get(5s)` → TIMEOUT-статус вместо зависшего эндпоинта (клиентский REQUEST_TIMEOUT_MS 20 c не задевается); DISABLED когда интеграция выключена конфигом (test-профиль: minio/janus) — бины через **ObjectProvider** (прямого инжекта нет — контекст теста без MinioClient/JanusAdminClient не поднялся бы); DOWN/TIMEOUT → WARN `[health] имя: причина` в серверный лог. (2) **Container-грань** — ОДИН `docker ps -a --format "{{.Names}}\t{{.State}}\t{{.Status}}"` (включая СТОПНУТЫЕ — «exited» и есть главный диагноз), резолв имён через `ContainerLogCollector.resolveContainer`, health из суффикса `(healthy)` колонки Status (**unhealthy проверяется РАНЬШЕ healthy — подстрока!**; у janus/coturn HEALTHCHECK в compose нет — NONE это норма); docker недоступен/выключен (конфиг `oak.health.docker.{enabled:true,containers:postgres,minio,janus,coturn,timeout:PT10S}`) → грань UNKNOWN с причиной, ответ жив. Имена контейнеров в argv НЕ участвуют (резолв по выводу ps) — инъекций нет. **СОПУТСТВУЮЩИЙ ФИКС резолва:** `resolveContainer` += третья ветка `-{name}$` — dev-compose задаёт `container_name: oak-postgres/oak-minio/oak-janus/oak-coturn`, которые НЕ матчатся ни точным именем, ни `-{name}-N`: контейнерные хвосты в server-logs.zip на этой машине весь раунд логгирования молча отдавали «unavailable: no running container matches» (найден разведкой этого раунда; теперь матчатся). Тесты: NEW `ServerHealthServiceTest` (7: H2-postgres-UP / DISABLED-грани / janus-вердикты success-error-transport / coturn TCP up+down через ServerSocket(0) / docker-грань running+healthy+exited+ABSENT / malformed+comma-имена / docker-фейл → UNKNOWN) + NEW `ServerHealthIntegrationTest` (401/403/200: postgres UP на H2, minio/janus DISABLED, контейнеры UNKNOWN при `oak.health.docker.enabled=false`; coturn-статус только exists — на dev-машине порт может слушать) + widened-кейс resolveContainer. — **521 тест (было 510)**. Смоук: `docker compose stop janus` → строка janus красная ≤30 c + server-logs.zip с НЕ-пустыми containers/*.log (побочный эффект фикса); плебейский curl → 403 + `[api] 403` в логе.
- [ ] **Бэклог (консультация 2026-10-06, выкладки сохранены): git-хостинг проекта.** Рекомендация — **GitHub, приватно, оба репо рядом** (oak-server + space-client, org или один аккаунт). Решающий аргумент — CI: free-тариф GitHub даёт 2000 мин Actions/мес на приватные репо против ~400 мин shared-раннеров gitlab.com, а наш гейт (mvn test 521 тест + npm test + typecheck + build) — это минуты на прогон, Actions хватает с запасом, GitLab-квота впритык. Соло-разработка → ценность хостинга = бэкап + CI, коллаборационные фичи не нужны. Self-hosted-эстетика OAK: GitLab CE — перебор (~4 ГБ RAM, тяжёлая эксплуатация); лёгкая альтернатива — Forgejo/Gitea (контейнер к compose-периферии, сотни МБ); компромисс — GitHub основной + зеркало в Forgejo позже при желании. Секреты чисты с security-раунда 2 (env/.env вне репо, шаблон env/.env.example) — публикация/приватность безопасна в обоих вариантах; до релиза — приватно. Перед выбором проверить доступность push с dev-машины (прецедент: Docker Hub с неё недоступен): `git ls-remote` / `ssh -T git@github.com`.
- [x] **Пинг-индикатор соединения — серверная половина (2026-10-07; ДЕПЛОЙ: сервер первым, аддитивный STOMP-маппинг — миграций и wire-изменений НЕТ, старый клиент новый маппинг не зовёт).** НОВЫЙ `websocket/PingHandler` (`@Controller`, `@MessageMapping("/ping")`): клиент шлёт на `/app/ping` свой epoch-millis-таймстамп (голое JSON-число — Long-тело-прецедент `/topic/channels.changed`), сервер эхирует значение ДОСЛОВНО тому же юзеру `convertAndSendToUser(sender, "/queue/ping")` (клиент подписан на `/user/queue/ping`); RTT считает клиент как now − эхо — сервер таймингов не добавляет и часовых предположений не делает. Null principal/payload — тихий no-op. Old-server-контракт: сервер без маппинга молча дропает SEND — клиент читает «нет эха» как unsupported, НЕ как ошибку (обратная совместимость бесплатна, см. клиентскую половину). **Security-проход:** получатель всегда аутентифицированный отправитель (IDOR невозможен — адресат выводится из Principal, не из payload); 1 входящий кадр = 1 исходящий в ТО ЖЕ соединение, fan-out нет → rate-limit не нужен (спамер грузит только собственный сокет; задокументировано в javadoc); тело не логируется; non-number body умирает в Jackson-конвертации без краха сессии; `SendDestinationInterceptor` пропускает `/app/**`; `/user/queue/ping` — user-destination (мапится в суффикс своей сессии, чужие эхи недоставляемы по построению). Тесты: NEW `PingHandlerTest` (3: эхо отправителю дословно; null principal — тихо; null payload — тихо). — **524 теста (было 521)**. Клиентская половина — space-client/CLAUDE.md. Апгрейд на потом: медиа-RTT в UI звонка через RTCStatsReport (candidate-pair.currentRoundTripTime) — клиент-only.
- [ ] **Бэклог: активность «Играет в …» — серверная половина (оценка M, wire ПАРНЫЙ).** Модель: юзер сам регистрирует игру на клиенте указанием на exe (детект по имени процесса); каталога лого/названий НЕ заводим (внешние API-key сервисы обложек против духа self-hosted; v1 — текст-only, кастом-иконка через MinIO-аватарный пайплайн потом). Активность эфемерна как presence → IN-MEMORY трекер username→activity (персистентность НЕ нужна: умирает с disconnect, клиент перерепортит после реконнекта). Wire: `UserSummary` += nullable `activity` (в конец, ПАРНЫЙ деплой со space-client); пуш готового UserSummary в `/topic/users` — аватарный прецедент (клиентский ростер уже перекрашивается по нему), MessageResponse не трогаем. Эндпоинт: REST `POST /api/users/me/activity` `{activity|null}` (гейт — любой авторизованный). Security при реализации: лимит длины (~64) + rate-limit на смену (DirectContactGate/LoginRateLimiter-идиома — иначе флип каждые 100 мс = broadcast-спам всем подписчикам /topic/users); строка = самовыражение уровня ника. Приватность: список процессов наружу НЕ уходит — только имя, которое юзер зарегистрировал сам.
- [ ] **Схлопнуть миграции V3–V5 в чистый набор ПЕРЕД УПАКОВКОЙ (#470).** V5 существует только потому, что кривая V4 успела примениться на живой dev-базе (checksum нельзя менять post factum); свежий инстанс гоняет три миграции там, где хватает двух. Перед релизом: слить identity-блок V5 внутрь V4 (V3 не трогать — она чистая), проверить на THROWAWAY-базе (прецедент oak_v1check из #482: чистая база исполняет V1→V4 с нуля + validate чист), живую dev-базу перебазлайнить (DROP TABLE flyway_schema_history + рестарт с baseline-on-migrate — рецепт «чистый V1» из #482; данные V3/V4/V5-колонок совместимы, identity на пустой-непустой колонке через ADD GENERATED валиден только пока таблица пуста — на dev тредов нет, проверить `SELECT count(*) FROM discussions` ПЕРЕД сносом). ГРАБЛЯ (уже пойманы в #482): удаление файла миграции из src НЕ удаляет его из target/classes — `mvn clean` обязателен; TaskStop не убивает spring-boot:run-JVM (taskkill /T /F по 8080-листенеру).
- [x] **Приватные каналы (Locked/Hidden) — `VIEW_CHANNEL` + пер-пользовательские оверрайды.** Discord-style: TEXT-канал / VOICE-комната ограничивает вход списком ролей и ПОЛЬЗОВАТЕЛЕЙ. **Движок:** (1) `Permission.VIEW_CHANNEL` дописан в САМЫЙ КОНЕЦ enum (ordinal 28; вставка «в конец группы» ремапила бы биты всех последующих групп — правило в javadoc исправлено); (2) НОВАЯ сущность `ChannelUserOverride` (channel,user,allow,deny; unique(channel_id,user_id)) + репозиторий (`findWithUserByChannelId` c `@EntityGraph`; derived-имя `findByChannelIdWithUser` НЕ парсится — Spring читает суффикс как свойство) + REST `/api/channels/{id}/overrides/users[/{userId}]` (GET открыт, PUT/DELETE под MANAGE_CHANNELS; `ChannelUserOverrideResponse` несёт username — клиенту не джойнить); (3) **Слоистая резолюция** `AuthorizationService.resolveChannelMask(User,Channel)`: base → слой @everyone-строки → слой ролей (allow|/deny|, deny внутри слоя побеждает) → слой личной строки юзера — ЗАМЕНА прежней аккумуляции с глобальным deny-wins (при ней role-allow НИКОГДА не перебил бы @everyone-deny — приватные каналы невозможны; поведенческое изменение осознанное, регрессия запинана тестом roleAllowBeatsEveryoneDeny); (4) **VIEW — мастер-гейт** в `hasInChannel`: без VIEW любой другой permission = false (скрытый канал 403-ит всё, хотя @everyone-base всё ещё даёт READ/SEND) — один гейт закрыл ВСЕ существующие call-sites (messages/read-state/voice/mentions/myPermissions); (5) `GET /api/channels` — viewer-фильтрованный: скрытые (!VIEW) omitted, `ChannelResponse` += 5-е поле `boolean locked` (per-viewer: TEXT без READ / VOICE без CONNECT) → **wire-деплой ПАРНЫЙ**; create-broadcast остаётся `locked=false` (каналы создаются публичными); (6) `/topic/channels.changed` — ping телом `Long`-timestamp после КАЖДОЙ мутации оверрайдов (role+user × upsert+delete) — broadcast без данных, ничего не утекает; клиент делает diff-refetch; (7) `ChannelService.delete` += purge `channel_user_overrides`; (8) **`RoleSeeder.ensureEveryoneViewChannel()`** — безусловный ре-грант VIEW-бита @everyone при каждом старте: БД, засеянная ДО появления бита, иначе делает ВСЕ каналы невидимыми всем кроме OWNER (instance-wide darkness — ADD_REACTIONS-прецедент ручной миграции тут неприемлем). **Интерсептор** переименован `DmSubscriptionInterceptor` → `ChannelSubscriptionInterceptor`: TEXT/VOICE-темы гейтятся по `READ_MESSAGE_HISTORY` (НЕ по VIEW — Locked-канал остаётся в списке аутсайдера, клиент подписывается на его тему, и VIEW-гейт пропустил бы LIVE-тела сообщений при 403 истории; скрытые каналы ловит мастер-гейт). Тесты: `PrivateChannelIntegrationTest` (миграция бита; hidden невидим аутсайдеру, виден member-allow/role-allow/owner; locked-флаг per-viewer TEXT+VOICE; мастер-гейт: GET/POST сообщений 403 + read-states без канала без единого явного READ-deny; user-override CRUD) + `ChannelOverrideServiceTest` (ping на все 4 мутации; void-метод requireServer стабится doThrow, не when()). 204 теста. Смоук pending.
- [x] **Janus token_auth + per-room pin/secret + принудительный mute/kick (безопасность SFU).** Закрыты две дыры ревью: (1) Janus стоял на дефолтном конфиге образа — НИКАКОЙ аутентификации (любой, знающий ip:8088, мог `list` → join → **слушать любую комнату**; admin API 7088 торчал наружу с дефолтным `admin_secret = "janusoverlord"`); (2) модераторские mute/kick были чисто кооперативными (враждебный клиент игнорирует `/user/queue/voice.control` и продолжает вещать). **Модель доступа (apisecret отвергнут — общий секрет без per-user/отзыва, строго слабее):** `token` — per-visit, SecureRandom-hex, регистрируется Admin API `add_token` с `plugins=[videoroom]`, клиент несёт его на каждом запросе (ПОЛЕМ ТЕЛА на POST, `?token=` на GET long-poll); `pin` — per-channel `HMAC-SHA256(adminSecret, "oak-voice-pin:"+id)`, обязателен в `join` (закрывает «легитимный юзер входит в чужую комнату перебором id» — токены plugin-scoped, НЕ room-scoped); `secret` — HMAC с доменом `"oak-voice-secret:"`, ТОЛЬКО серверу: им подписаны `kick`/`moderate` (без него 429), гриферство чужих комнат закрыто. **Комнаты создаёт СЕРВЕР** (`ensureRoom`: эфемерная контроль-сессия, exists → create с pin+secret; create-гонка лечится рек-check exists) — клиент больше не зовёт create. `service/JanusVoiceAccessService` (interface + `HttpJanusVoiceAccessService` @ConditionalOnProperty `oak.janus.admin-enabled` + `Noop...` — калька Minio-паттерна, test-профиль отдаёт константы "test-token"/"test-pin"): `issueVoiceToken` (карта username→(channel→tokens) кап 8, эвикция старейшего через remove_token; `bootstrapCleanup` = list_tokens+remove_all на старте — stored-токены переживают рестарт сервера, умирают только с рестартом Janus), `revokeTokens` (по ПАРЕ (channel,user) — кик из комнаты А не рвёт голос в Б; re-entry честного юзера не блокируется — новый /room = новый токен), `forceKick`/`moderateAudio` — fire-and-forget на однопоточном daemon-экзекуторе `oak-janus-admin` (STOMP-поток не блокируется; действия одной комнаты упорядочены): mint токен → create session → attach → **passive join с pin** → poll `joined` → `publishers[]` → матч `display == username` (ВСЕ сессии юзера, зеркало `tracker.kick`) → `kick{id,secret}` / `moderate{room,id,mid,mute,secret}` → destroy. **Грабли videoroom 1.1.4 (все проверены живым curl):** `unmoderate` из старых док НЕ существует — есть только `moderate` с ОБЯЗАТЕЛЬНЫМ `mute:true|false`; `listparticipants` НЕ возвращает streams/mid — единственный источник audio-mid = `joined`-событие passive-join (join без публикации легален); join без pin → 429, с неверным → 433; kick НЕ блокирует re-entry (для этого revokeTokens). `VoiceControlHandler`: mute → `tracker.setMuted` + `moderateAudio` (кооперативный voice.state-пуш ОСТАЁТСЯ — мгновенный UI честного клиента); kick → `tracker.kick` + `forceKick` + `revokeTokens` + KICKED; **deafen БЕЗ принудительного слоя** (moderate глушит только ИСХОДЯЩИЕ m-line — «заставить враждебного не слышать» API нет). `JanusProperties` += `adminUrl`/`adminEnabled`; `RestClientJanusAdminClient` — первый исходящий HTTP сервера (Spring `RestClient`, 3с/10с; poll-таймаут = легальное «нет событий»). **Wire:** `RoomResponse(janusUrl, roomId, token, pin)` — 4 поля, деплой ПАРНЫЙ. Конфиг: локальный образ `oak-janus:secured` (`env/janus/Dockerfile`, `build: ./janus` в compose) с ДВУМЯ запечёнными конфигами: `janus.jcfg` (дефолт + `token_auth = true` + `admin_secret = "janus_admin_secret"` == `.env` `JANUS_ADMIN_SECRET`) и `janus.transport.http.jcfg` с `admin_http = true`; после правки конфигов — `docker compose build janus`. **ГРАБЛИ ЖИВОЙ ОТЛАДКИ (полдня охоты):** (1) в canyan-дефолте Admin/monitor API ВЫКЛЮЧЕН (`admin_http = false`) — с token_auth Janus фаталится на старте («No Admin/monitor transport is available, but the stored token based authentication mechanism is enabled... giving up!», exit 1, **логи ПУСТЫ** — буфер stdout умирает с процессом; контейнер рестарт-циклит «Restarting (1)» и выглядит как «мёртвый порт»); (2) **этот билд игнорирует токен в query на POST** (`?token=` → 403) и принимает его в query только на GET long-poll — все POST несут `"token"` ПОЛЕМ В ТЕЛЕ (правки в `RestClientJanusAdminClient.addTokenField` и клиентском `JanusClient.withBodyToken`; проверено живым curl); (3) при зависании/краше демона Docker Desktop утекает фантомная аллокация порта (у нас 2000): контейнер рестарт-циклит с «Bind for 0.0.0.0:2000 failed: port is already allocated» при ПУСТОМ netstat — лечится только полным перезапуском Docker Desktop (прецедент: TURN 3478→34780 в compose — тот же класс утечки). **Деплой ПОСЛЕДОВАТЕЛЬНЫЙ: сервер → клиент → `docker compose up -d --build janus` → рестарт oak-server** (token_auth мгновенно валит старых клиентов; bootstrap-чистка сносит устаревшие токены). Остаточные риски v1: display-спуф (матчатся оба, кик выкинет обоих); revoke не рвёт живую WebRTC — сессию жнёт Janus ≤ session_timeout (~60 с), мгновенность даёт plugin-kick. Тесты: `JanusVoiceAccessServiceTest` + `RoomControllerIntegrationTest` (token/pin). 214 теста. Смоук pending.
- [x] **Приватные каналы — enforcement живых сессий (кик при потере CONNECT / при удалении канала).** Закрыт join-time-only пробел: сидящие в голосовой комнате переживали флип канала в Locked/Hidden бессрочно. Новый `websocket/VoiceAccessEnforcer` — единственный владелец рецепта принудительного выхода `forceLeave(channelId, username, byUsername)`: `tracker.kick` → `voiceAccess.forceKick` → `revokeTokens` → KICKED-пуш (константа `VOICE_CONTROL_QUEUE` переехала сюда из `VoiceControlHandler`, чей `/voice.kick` теперь делегирует и потерял зависимость `SimpMessagingTemplate`). Методы: `enforceConnect(channelId, actor)` — snapshot-фильтр по каналу (**пустая комната → ноль SQL**, общий случай) → `hasInChannel(CONNECT)` по каждому сидящему (`findUser` пуст = удалённый юзер = кик) → forceLeave потерявшим; `kickAll(channelId, actor)` — всем без пересчёта прав и без обращений к БД (удаление канала: права мутны). Хуки: 4 мутации `ChannelOverrideService` (после `pingChannelsChanged`, ВНУТРИ `@Transactional` — AUTO-flush выталкивает несмочённый оверрайд до запросов маски на том же persistence context; after-commit-хук при `open-in-view:false` открывал бы транзакцию на каждый запрос, а его исключения всё равно летят в вызывающего; риск pre-commit-кика = класс риска ping'а, откат самоизлечивается re-join'ом) + `ChannelService.delete` (`kickAll` сразу после загрузки канала, ДО чистки детей: 404-гейт шорткортит, LEFT/KICKED-пуши ссылаются на ещё существующий канал, FK-провал чистки самоизлечивается). Биты изменений НЕ сниффаются (VIEW — master-gate, эффекты транзитивны). Не-баг: админ БЕЗ ADMINISTRATOR, запрещающий @everyone CONNECT сидя в комнате, кикает сам себя (Discord-faithful; OWNER невосприимчив через ADMINISTRATOR→ALL). **Wire не менялся** (KICKED-пуш переиспользован as-is, клиент уже делает полный teardown + refetch) — деплой НЕ парный, только сервер. Тесты: `VoiceAccessEnforcerTest` (7 unit: лузер/кипер, пустая комната без БД, неизвестный канал, удалённый юзер, kickAll без auth, порядок 4 шагов) + `ChannelOverrideServiceTest`/`ChannelServiceTest` (хук-verify + 404-никого-не-кикает) + `PrivateChannelIntegrationTest` (+2: `stageInVoice` через `tracker.enter` с ОБЯЗАТЕЛЬНЫМ `@AfterEach`-exit — tracker синглтон кэшированного контекста и НЕ транзакционен, иначе утечка сессий между тестами). 225 тестов. Смоук pending.
- [x] **Enforcement живых сессий — итерация 2 (хуки ролей, sweep токен-держателей, destroyRoom).** (1) **Хук ролей**: `RoleService.update` при РЕАЛЬНОМ изменении маски (сравнение до/после — косметическая правка не свипает) и `RoleService.delete` → `VoiceAccessEnforcer.enforceEverywhere(actor)` — пересчёт CONNECT по КАЖДОЙ обитаемой комнате (роль бьёт произвольных держателей в произвольных каналах; необитаемая комната стоит одно in-memory snapshot-чтение); `MemberService.assignRoles` → `enforceConnectForUser(target, actor)` — замена ролей меняет маску одного юзера, свипаются только ЕГО комнаты. (2) **Два реестра на входе свипов**: `JanusVoiceAccessService.tokenHoldersByChannel()` (channelId → usernames живых токенов) — враждебный non-STOMP участник невидим STOMP-трекеру, но его токен минчен через `/room` под реальным именем, поэтому он в карте; `enforceConnect` свипает UNION трекера и токен-реестра (forceKick достаёт по display мгновенно, revokeTokens убивает токен — медиа умирает ≤ session_timeout даже без display-матча). (3) **destroyRoom**: `ChannelService.delete` после `kickAll` зовет `voiceAccess.destroyRoom` (видеорум destroy с admin-secret подписью; fire-and-forget на ТОМ ЖЕ однопоточном `oak-janus-admin` — сабмит после киков ⇒ исполнение после них) — non-STOMP rogue больше не держит комнату живой до рестарта шлюза. Общий хелпер `evictIfLostConnect`; пустая комната без токенов по-прежнему = ноль SQL. НЕ переделывал авторизацию: token/pin/secret-модель нетронута, добавлен только источник входа свипа. Тесты: `VoiceAccessEnforcerTest` (+3: token-sweep невидимки, per-user, everywhere из обоих реестров), `JanusVoiceAccessServiceTest` (+2: `tokenHoldersByChannel` зеркало+после-revoke; destroy подписан secret — через sync-ядро `destroyRoomNow`, публичный метод асинхронен), `RoleServiceTest` (новый: маска-изменилась → everywhere / косметика → ничего / delete → everywhere), `ChannelServiceTest` (порядок kickAll→destroyRoom→purge; 404 — ничего), `PrivateChannelIntegrationTest` (+2: PATCH @everyone минус CONNECT → сидящий evicted, косметика — нет; снятие роли у одного члена → evicted только он). 235 тестов. Смоук pending. Сервер-only, wire не менялся.
- [ ] **Enforcement живых сессий — осознанные ограничения (follow-up).** (1) Токен+pin, слитые третьему лицу, которое НИКОГДА не имело доступа: его имени нет в карте токенов, display произвольный — закрытие = ротация pin (соль с поколением комнаты) + массовый revoke; но инсайдер-лик своих кред эквивалентен «юзер сам транслирует аудио наружу» (не защищает и Discord) — осознанно НЕ делаем. (2) Фоновые (non-active) клиентские сессии обрабатывают `channels.changed` — сейчас реагирует только активная (stale `serverChannelIds`/бейджи до переключения инстанса; голос ведёт только активная — на enforcement не влияет). (3) Принудительное снятие ЧУЖИХ WS-подписок при флипе прав: простой брокер не даёт API, живая подписка доставляет до отписки/реконнекта (наши клиенты лечатся channels.changed-refetch'ем).
- [x] **Self mute/deafen (сервер) + rename канала (PATCH /api/channels/{id}/name).** (1) **Self-мьют без прав, Discord-семантика**: `VoiceControlHandler.mute/deafen` получили self-ветку (`targetUsername == actor` → БЕЗ `canControl`/`auth.findUser`; null-валидация остаётся; mute и для себя идёт через forced-слой `moderateAudio` — единообразно). **Ключевой фикс дизайна (наивный вариант дал бы ДЫРУ — self-unmute снимал бы модераторский мьют):** `VoiceMembershipTracker` делит состояние на 4 множества (`selfMuted/modMuted/selfDeafened/modDeafened`); `setMuted/setDeafened` (модераторские) пишут mod-, новые `setSelfMuted/setSelfDeafened` — self-множества; `isMuted` = UNION; **deafen ⇒ mute (инвариант трекера, вариант «Б»)**: implied-мьют живёт в СВОИХ множествах per-origin (`impliedSelfMutedByChannel`/`impliedModMutedByChannel`), пишется/чистится ВМЕСТЕ с deafen того же origin — оглушённый не должен транслировать, не слыша комнаты; явный мьют, поставленный рядом, ПЕРЕЖИВАЕТ undeafen (клиентам ничего не нужно помнить — пуш несёт готовые флаги, клиент НЕ менялся вовсе); origin-флаги на wire включают implied (deafened показывает ОБЕ марки в цвете происхождения); **модераторский deafen получил forced-слой** — implied-мьют гонится через `moderateAudio` (заглушить-по-слуху нельзя, а вот микрофонную часть deafen'а — можно); все ветки mute/deafen хендлера зовут `moderateAudio(tracker.isMuted(...))` — forced-слой следует ОБЪЕДИНЕНИЮ, а не переключаемому значению (явный unmute под живым deafen не открывает аудио на шлюзе); граница: «Unmute» на заглушенной цели «не работает» до undeafen (у Discord так же); implied-мьют наследует выживание: self — умирает на leave вместе с селф-deafen, mod — переживает ревход + репаблиш. чистка на leave — ТОЛЬКО self-множества (селф-тумблеры сбрасываются ревходом), **модераторские ПЕРЕЖИВАЮТ выход/кик — серверный мьют снимается только модератором** (живой смоук: ревход снимал сервер-мьют), при следующем `enter` пережитое состояние РЕПАБЛИШИТСЯ (у всех LEFT-дельта снесла марки, ревошедший с новым паблишер-сеансом должен скомплаиться заново — forced-слой на ревходе НЕ переигрывается, комплайенс через пуш). Кнопка клиента просто шлёт `/app/voice.mute` на себя — эхо `/topic/voice.state` само комплаится и красит строки у всех. **Итерация 2 (origin на wire):** `VoiceControlState` += `selfMuted`/`selfDeafened` И `modMuted`/`modDeafened` — **ОБА флага явно** (клиентский вывод `mod = union ∖ self` НЕОДНОЗНАЧЕН, когда оба true: union=true+self=true не определяет mod — живой смоук терял мод-флаг при селф-мьюче цели: жёлтый глиф вместо красного + «Server Mute» не flipping'ался в «Unmute»); первые два поля остались UNION (семантика комплаяенса) — **STOMP-DTO-расширение = ПАРНЫЙ деплой** (клиентские мапперы не терпят неизвестных полей). Нюанс: OWNER-self и раньше проходил canControl — не трогали. (2) **Rename**: `RenameChannelRequest(@NotBlank @Size(max=100))` + `PATCH /api/channels/{id}/name` (`ChannelController.rename` → `ChannelService.rename`): `requireServer(MANAGE_CHANNELS)` (консистентно с create/delete/overrides — все серверно-скоупед), 404 unknown, **400 для DM/GROUP_DM** (иначе админ переименовал бы чужое ЛС через админ-маршрут), trim+save, ping `CHANNELS_CHANGED_TOPIC`; **ответ несёт per-viewer `locked`** (новый `lockedFor(channel, mask)` извлечён из `list()` и переиспользован в обоих местах — `ChannelResponse.from(channel)` хардкодит locked=false, без этого оптимистичный апдейт клиента мигал бы лок→анлок). Маршрут на сегмент длиннее DM-ового PATCH `/{id}` (DmChannelController) — иначе ambiguous mapping. Тесты: `VoiceMembershipTrackerTest` (+3: self-unmute НЕ снимает мод-мьют; self+mod комбинируются/чистятся; leave чистит deafen-множества), `VoiceControlHandlerTest` (новый: self без auth + forced-слой; чужой без права — тишина; null — no-op), `ChannelServiceTest` (+3: rename trim+ping+viewer-locked; 404; DM-400), `RbacIntegrationTest` (+rename: 403/200/blank-400/101-симв-400/404). 246 тестов. Смоук pending. **Деплой ПАРНЫЙ** (сервер → клиент): rename-эндпоинт аддитивный, но расширение `VoiceControlState` валит старого клиента на unknown-полях.
- [x] **Устойчивый к неготовой периферии старт (ждать Postgres, а не падать).** Поймано на dev: запуск `spring-boot:run` до/во время подъёма `env/docker-compose.yml` ронял приложение на создании SessionFactory (`ddl-auto=update` требует соединение) с запутанным `PSQLException: Ошибка при попытке подсоединения` ← `EOFException` в `ConnectionFactoryImpl.enableSSL` — на Windows Docker-прокси **принимает** TCP на проброшенном порту без живого контейнера, поэтому вместо честного «Connection refused» драйвер получает обрыв на SSL-хендшейке. Фикс: `spring.datasource.hikari.initialization-fail-timeout: 60000` в `application-dev.yml` — при создании пула Hikari ретраит соединение до 60 с вместо мгновенного фейла (рентайм-фейлы по-прежнему fail-fast; тест-профиль H2 не затронут — правка dev-only). Рассматривался и отвергнут `BeanPostProcessor`-обёртка DataSource с лог-подсказками «waiting for PostgreSQL…» — работает, но кода на порядок больше ради косметики; прав порядок запуска (`docker compose up -d` → `docker ps` до `healthy` → `spring-boot:run`) остаётся основным путём, конфиг — только предохранитель от гонки после перезагрузки машины/Docker Desktop.
- [x] **Settings-раунд сервера: self-rename username (enforcement CHANGE_NICKNAME) + connection info на ServerProfile.** (1) **Self-rename**: `PATCH /api/users/me/username` (`RenameUsernameRequest` `@NotBlank @Size(3..32)`; ответ `UserRenamedResponse(UserResponse, AuthResponse)`) — `requireUser` → `auth.requireServer(user, CHANGE_NICKNAME)` (первая enforcement-точка права; прецедент `requireManageServer` в ServerProfileController) → `AuthService.changeUsername(User, newUsername)`: trim → same-имя 400 → `existsByUsername` **409** → save с catch `DataIntegrityViolationException` → 409 (гонка, идиома register) → **revoke всех refresh + `issueTokens(user, newFamilyId())`** (калька changePassword). **Ключевая связка: JWT subject = username** — после rename ВСЕ выданные access-токены перестают резолвить юзера (до 8 ч), поэтому endpoint обязан вернуть свежую пару, а переименованный клиент обязан переподнять STOMP (principal зашит на CONNECT; PresenceTracker/VoiceMembershipTracker/CallSignaling адресуются по имени — presence сам чинится OFFLINE(old)/ONLINE(new) при реконнекте переименованного). **`RoleSeeder`**: `CHANGE_NICKNAME.bit` в `EVERYONE_DEFAULTS` + `ensureEveryoneChangeNickname()` — безусловный ре-грант бита @everyone на каждом старте (зеркало `ensureEveryoneViewChannel`; иначе БД, засеянная до бита, показывала бы фичу только OWNER). История сообщений подхватывает новое имя сама (author — живой FK); старые `@ник`-упоминания остаются текстом (задокументированный MentionParser-tradeoff); личные громкости в voiceVolumes клиента привязаны к старому имени (клиентская сторона). (2) **ФИКС, вскрытый тестом**: `JwtAuthenticationFilter` при криптографически валидном JWT, чей subject больше не резолвится (переименованный/удалённый юзер), кидал необработанный `UsernameNotFoundException` → 500 вместо 401 — теперь catch → `sendError(401, "unknown user")` + short-circuit (как ban-ветка); rename делает это состояние НОРМАЛЬНЫМ на срок жизни старых access-токенов. (3) **Connection info**: `ServerProfile` += nullable `connectionHost`/`connectionPort` (хранилище для будущего remote-provisioning — клиент-настройщик запишет их; сид-значения "localhost"/8080 только для НОВЫХ строк, dev-строка остаётся NULL до ручной записи) + `PATCH /api/server/connection` (`UpdateConnectionInfoRequest`: поля nullable = не менять, blank host → 400, порт 1..65535; MANAGE_SERVER; `publish` → `/topic/server`; отдельный сегмент от name-PATCH) + `ServerProfileResponse` += оба поля В КОНЕЦ (**wire-деплой ПАРНЫЙ** — клиентские мапперы не терпят unknown-полей). Клиентская часть — `../oak-client/CLAUDE.md`. Тесты: `UsernameChangeIntegrationTest` (5: happy — старый access 401 / старый refresh 401 / новая пара валидна / освобождённое имя берёт другой юзер; дубликат 409; blank/2/33 симв 400; same 400; без права 403 + `ensureEveryoneChangeNickname` восстанавливает бит) + `ServerProfileIntegrationTest` (+2: GET несёт сид-адрес; PATCH частичный/blank/невалидные порты; non-owner 403). 255 тестов. Смоук pending; деплой ПАРНЫЙ (сервер → клиент).
- [x] **RBAC — итерация 1 (серверный фундамент):** полный каталог `Permission` (bitmask) + `Role`/`RoleKind`/`ChannelRoleOverride`/`User.roles`+`banned`; `RoleSeeder` (`@everyone` + `OWNER` + бутстрап владельца); `AuthorizationService` (effective/hierarchy/channel-override); REST roles CRUD+reorder, members (assign/kick/ban), `/api/users/me/permissions`, `/api/permissions`; enforcement MANAGE_CHANNELS/SEND_MESSAGES/READ_MESSAGE_HISTORY; ban-чек login+CONNECT; push `/topic/roles`|`/topic/members`. `RbacIntegrationTest`. См. «Текущее состояние» и грабли выше.
- [x] **RBAC — итерация 2 (завершение серверной части):** REST channel-overrides CRUD (`/api/channels/{id}/overrides/{roleId}`, upsert/list/delete, `MANAGE_CHANNELS`; allow/deny ↔ bitmask); enforcement `ATTACH_FILES` (`MessageService.create`, когда есть attachmentKey — channel-scoped) + `CONNECT` (`RoomController.room` REST + `VoiceMembershipHandler.join` STOMP — channel-scoped). `RbacIntegrationTest` += 3 (override-CRUD / ATTACH_FILES / CONNECT). 57 тестов. *Голосовой mute/deafen/kick перенесён в итерацию 4 (end-to-end); клиентский гейт+UI — итерация 3.*
- [x] **Channel deletion endpoint:** `DELETE /api/channels/{id}` (`ChannelController` + `ChannelService.delete`, `MANAGE_CHANNELS` + 404 если канал уже удалён). Перед `channelRepository.delete` чистит зависимости — `messageRepository.deleteByChannelId` + `channelRoleOverrideRepository.deleteByChannelId` (derived-deletes с `@Transactional`): FK без `ON DELETE CASCADE` под `ddl-auto=update` иначе отверг бы channel-DELETE. Пушит `/topic/channels.deleted` (тело — `Long` channelId) отдельной темой от `/topic/channels` (создание), чтобы клиент различал создание/удаление (`MainController.onChannelDeleted` → `removeChannelIfPresent` + авто-выход из голоса при удалении активного voice-канала). `RbacIntegrationTest.deleteChannel_requiresManageChannelsAndClearsDependents`.
- [x] **RBAC — итерация 3:** клиентский UI — окно «Server Settings» (Roles/Members/Channels, tri-state channel-overrides editor) реализовано в `oak-client` (см. его `CLAUDE.md`, раздел RBAC). Серверная часть завершена в итерации 2.
- [x] **RBAC — итерация 4 (голосовой mute/deafen/kick end-to-end):** `VoiceMembershipTracker` += per-(channel,user) mute/deafen state + `kick(username,channelId)` (force-exit сессий → `LEFT`), чистка state на leave; broadcast `/topic/voice.state` (`VoiceControlState{username,channelId,muted,deafened}`). `VoiceControlHandler` (`@MessageMapping /app/voice.mute|deafen|kick`, тело `VoiceControlCommand{channelId,targetUsername,muted,deafened}`): чек `MUTE_MEMBERS`/`DEAFEN_MEMBERS`/`MOVE_MEMBERS` + `canActOn` (через `AuthorizationService` — eager-roles, безопасно на STOMP-потоке); кик → targeted `/user/queue/voice.control` (`VoiceControlEvent{KICKED}`) цели. Кооперативный (сервер — источник правды, клиент цели подчиняется). `VoiceMembershipTrackerTest` +3. 61 тест.
- [x] **Instance identity (имя + иконка сервера):** singleton-сущность `ServerProfile` (одна строка: `name`, `avatarKey`, `avatarOriginalKey`) — инстанс OAK получает собственный profile в БД (гильдии нет; инстанс и есть сервер). `ServerProfileSeeder` (`ApplicationRunner @Order(1)`, idempotentный) сеет дефолт `name="OAK Server"` при первом старте. `ServerProfileController` (`/api/server`): `GET` открыт любому залогиненному (клиент тянет имя/иконку при коннекте), `PATCH` имени + two-phase иконки (slot/original/confirm/remove/lazy-original; префикс ключа `server-avatars/`, метод `newServerAvatarKey` добавлен в signer **×3** — interface/MinIO/Noop, иначе test-профиль не компилируется) — всё под **`MANAGE_SERVER`** (первая точка enforcement этого права: `auth.requireServer(auth.requireUser(username), MANAGE_SERVER)`). Каждый мутирующий эндпоинт пушит `/topic/server` (`ServerProfileResponse.from(profile,signer)` — полный profile, клиенты рефетчат иконку+имя). Singleton адресуется `findFirstByOrderByIdAsc` (НЕ хардкод `findById(1L)` — IDENTITY-sequence может уйти вперёд, и `@GeneratedValue(IDENTITY)` игнорирует ручной id). `ServerProfileIntegrationTest` (+4: GET 401 без токена, дефолт сида, OWNER full-flow, non-owner 403). 69 тестов.

- [x] **Упоминания (@user/@role/@everyone/@here) + read-state (непрочитанное):** серверный парсинг plain-text `@token` в structured-поле на read-time (БЕЗ БД-колонки — re-parse в `MessageService.toResponse`, единственная точка для create-broadcast и history-fetch). `service/MentionParser` (`@Component`): regex `@(\w+)`, резолв `@everyone`/`@here` по **литералу** (через `roleRepository.findByKind(EVERYONE)`, НЕ по name — сидер хранит name буквально `"@everyone"`), `@rolename` по `findByName` только если `mentionable && kind==CUSTOM` (non-mentionable роль НЕ consumedит токен → падает на same-named юзера), `@username` по `findByUsername` (всегда разрешено). Gating: `@everyone/@here` требуют `MENTION_EVERYONE` автора через `auth.hasInChannel` (channel-override-aware, OWNER/ADMIN bypass); новый Permission-констант НЕ нужно (`MENTION_EVERYONE` уже есть). DTO `MentionResponse(type,id,name,start,end)` (offset'ы для точечной подсветки подстроки); `MessageResponse` += `List<MentionResponse> mentions`. `MentionParser.expandToUserIds` (USER→{id}, ROLE→`findUsersByRoleId`, EVERYONE→all, HERE→`presenceTracker.onlineUsernames()`) — для mention-count и нотификаций. **Tradeoff:** rename user/role оставляет старые `@` простым текстом (re-parse на read) — задокументировано в DTO javadoc. Read-state: `entity/ChannelReadState(user,channel,lastReadMessageId)` unique (user_id,channel_id); `ReadStateService.ack`/`snapshot` (unread=`countByChannelIdAndIdGreaterThan(lastRead)`, mentionCount=parse+expand над freshest-200 unread окном, O(channels×unread×mentions) — acceptable small-instance); `ReadStateController` (`GET /api/read-states`, `POST /api/channels/{id}/read` body optional). **Bootstrap:** `AuthService.register` → `readStateService.seedInitialReadStates` (каждый канал → lastRead=current-latest, чтобы новичок не видел архив как unread). **Имя роли валидируется** `[A-Za-z0-9_-]{1,32}` в `RoleService.create/update` (`ROLE_NAME_PATTERN`) — чтобы plain-`@token` grammar не ломалась на пробелах. `MentionParsingIntegrationTest` + `MentionParserTest` + `ReadStateIntegrationTest`. 88 тестов.

- [x] **Друзья + личные переписки (DM), Discord-faithful:** социальный граф + 1:1 DM + групповые DM (3–10). **DM = переиспользование `Channel`** (`ChannelType` += `DM`/`GROUP_DM`) + новая сущность `ChannelParticipant` (membership; unique `channel_id`+`user_id`); сообщения/read-state/аттачи DM идут через существующие эндпоинты/сервисы. **Единственная ветка content-permission — в `AuthorizationService.hasInChannel`** (DM/GROUP_DM → `participantRepository.existsByChannelIdAndUserId`, вставлено **до** ADMINISTRATOR-short-circuit → даже OWNER инстанса не читает чужое ЛС); покрывает все существующие call sites (`MessageService.create/listByChannel`, `ReadStateService.*`, `ChannelService.myPermissions`). **Privacy-leak SUBSCRIBE (критично):** простой брокер НЕ авторизует `SUBSCRIBE`, поэтому любой мог бы читать чужое ЛС перебором `channelId` — новый `websocket/DmSubscriptionInterceptor` (`ChannelInterceptor`) гейтит `SUBSCRIBE /topic/channel.{id}` по membership (principal=username → `participantRepository.isParticipant`), для не-участников возвращает `null` (drop); зарегистрирован в `WebSocketConfig.configureClientInboundChannel` после `authChannelInterceptor`. **1:1 дедуп:** `Channel.dmKey` = `min_max` (unique nullable; Postgres считает NULL различными) — race двух одновременных созданий схлопывается в `DataIntegrityViolationException` → `entityManager.clear()` + re-find (`@Transactional(noRollbackFor=DataIntegrityViolationException.class)`, как reuse-pattern в `AuthService.refresh`). **Групповой DM:** `Channel.owner` (nullable `@ManyToOne User`, только GROUP_DM); owner-only add/remove/close; любой участник rename; leave-owner → transfer earliest-joined (`joinedAt`); cap 10 (при create и add). **Социальный граф:** направленная `entity/Relationship(source,target,type)` (`RelationshipType` `PENDING`/`FRIEND`/`BLOCKED`, unique `source_id`+`target_id`; FRIEND хранится **двумя строками** после accept — «мои друзья» = один range-scan `findBySourceAndType(me,FRIEND)`); `sendRequest`/`accept`/`removeRelationship`(cancel/decline/unfriend, idempotent)/`block`/`unblock` + **mutual-request auto-accept**; accept/promote через **UPDATE PENDING→FRIEND** (а не delete+insert) — иначе insert-before-delete action-queue роняет unique-constraint. `block` чистит friendship/запрос в обе стороны и гейтит DM/запрос (`blockedEitherDirection`) — **единственный гейт DM** (дружба не требуется, как Discord). Close-DM = delete (v1; `leftAt`-hide как future). Эндпоинты (`controller/RelationshipController` + `DmChannelController`): `/api/users/me/{relationships,friends/{userId},blocks/{userId}}` (GET/POST/PUT/DELETE) + `/api/channels/dm` (GET список, POST get-or-create), `/api/channels/group-dm` (POST), `PATCH /api/channels/{id}` (rename), `/api/channels/{id}/participants[/{userId}]` (add/remove-leave), `DELETE /api/channels/dm/{id}` (close — **отдельная сегментность** от public `DELETE /api/channels/{id}`, чтобы не collide). REST-principal = `UserDetails.getUsername()`, STOMP = `Principal.getName()` (username). STOMP-события (publisher по username через `convertAndSendToUser`): `/user/queue/relationships` (`RelationshipEvent`: FRIEND_REQUEST/FRIEND_ACCEPTED→обоим/FRIEND_REMOVED/BLOCKED/UNBLOCKED), `/user/queue/dm.channels` (`DmChannelEvent`: CREATED/PARTICIPANT_ADDED/PARTICIPANT_REMOVED/RENAMED/CLOSED → всем участникам), `/topic/channel.{dmId}` — сообщения без изменений. `DmChannelResponse` несёт участников + последнее сообщение (для сортировки/превью); **unread/mention клиент берёт из `GET /api/read-states`** (snapshot после DM-ветки включает DM-каналы участников) — без дублирования счётчиков. DDL (`ddl-auto=update` аддитивен): новые таблицы `relationships`/`channel_participants` (пустые, NOT NULL ок), nullable `channels.owner_id`/`dm_key` (без `columnDefinition default` — грабля касается населённых таблиц). Правки существующих: `ChannelService.list` исключает DM (`findByTypeNotInOrderByIdAsc`), `.delete` чистит participants+readstates; `ReadStateService.seedInitialReadStates` пропускает DM; mention-count в DM пересекается с участниками (defence-in-depth — snapshot и так исключает не-участников через `hasInChannel`). Тесты: `RelationshipIntegrationTest`, `DmPlumbingIntegrationTest`, `DmChannelIntegrationTest`, `GroupDmIntegrationTest`. **SUBSCRIBE-leak не покрывается MockMvc** (нет WS-слоя) — ручной smoke (d). 114 тестов.

- **Rename-модель (ник everywhere, 2026-09-15, задача #475/#472 — ДЕПЛОЙ ПАРНЫЙ со space-client):**
  три роли имён — `id` (технический ключ), `username` (= LOGIN: только вход/STOMP-principal,
  в UI не виден нигде кроме Settings→Account владельца), `nickname` (= НИК: NOT NULL
  семантически, UNIQUE В СВОЁМ МНОЖЕСТВЕ — может совпасть с чужим ЛОГИНОМ: 409-проба ников
  не должна раскрывать чужие логины; юникод-грамматика `[\p{L}\p{N}_-]{1,32}` БЕЗ пробелов =
  @-грамматика, кириллица разрешена). Колонка nullable-unique (грабля NOT NULL на населённой
  таблице), `User.displayName()` = ник??логин; конструктор ставит ник=логин (новые юзеры);
  `NicknameSeeder` (ApplicationRunner @Order(2)) backfill'ит старые строки идемпотентно.
  **MentionParser: токен-класс юникод `@([\p{L}\p{N}_-]+)` (кириллические ники/роли
  упоминаемы) и юзер матчится ТОЛЬКО `findByNickname` — логин принципиально не матчится
  (раскрыл бы связку логин→человек).** Friend-запрос (`RelationshipService.sendRequest`)
  тоже ПО НИКУ (`findByNickname`). Эндпоинты: `PATCH /api/members/{userId}/nickname`
  (`RenameMemberRequest @NotBlank @Size(1..32) @Pattern(юникод)`; себя — CHANGE_NICKNAME,
  чужого — MANAGE_NICKNAMES + requireCanActOn; 409 по занятому НИКУ) + `DELETE` (сброс к
  логину); пуш `/topic/members` (MemberEvent "renamed") + live `/topic/users`
  (`UserSummary.from(target, signer)` — клиентский ростер перекрашивает все поверхности).
  DTO-расширения (в конец, wire append-only, парные со space-client): `UserSummary`/`
  UserResponse` += `displayName`, `MemberResponse` += `nickname` (7-е), `RelationshipResponse`
  += `displayName` (6-е). `MessageResponse` НЕ тронут — клиент рендерит автора через живой
  ростер по username-ключу. `DmChannelResponse.lastMessageAuthorName` тоже несёт displayName()
  (смоук-фикс: превью DM-строки ПАЛИЛО логин автора; wire-поле то же, поменялось значение).
  Тесты: MentionParserTest (матч по нику; кириллица @Вася; логин
  не матчится) — 258 зелёных. Смоук pending; МИГРАЦИЯ dev-БД автоматическая (сидер + nullable-колонка).

- **Idempotency-key сообщений (антидубль при потерянном ответе, 2026-09-17, ДЕПЛОЙ ПАРНЫЙ со space-client):**
  `CreateMessageRequest` += 4-е поле `@Size(max=64) String idempotencyKey` (null = легаси-клиент =
  старое поведение с дублями; legacy 3-арг конструктор сохранён для старых вызовов/тестов).
  Сценарий: POST дошёл, строка создана, broadcast ушёл — ОТВЕТ потерян в морг сети → клиент
  ретраит С ТЕМ ЖЕ ключом → получает ОРИГИНАЛЬНЫЙ `MessageResponse` (без второй строки и без
  повторного broadcast). `service/MessageIdempotencyCache` — in-memory `ConcurrentHashMap`,
  composite-ключ `author + " " + key` (ключи per-ЮЗЕР — одинаковый ключ двух авторов независим;
  разделитель-пробел безопасен: логины без пробелов), TTL 2 мин (`TTL_MILLIS`), кап 10k записей →
  sweep просроченных, при насыщении живыми — полный clear (худший исход = дубль сообщения,
  никогда не аутаж). Проверка в `MessageService.create` ПЕРВОЙ строкой — ДО гейтов: запись могла
  появиться только для отправки, УЖЕ прошедшей гейты (namespacing), а реплей застрахован от
  изменившегося после оригинала состояния (например, удалённый reply-target больше не даёт 404).
  `put` — ПОСЛЕ broadcast (сбой кеша не может задвоить отправку). Ничего не персистится: рестарт
  забывает ключи = деградация к поведению до ключа для ретраев в полёте. **Тестовая грабля:
  in-memory кеш НЕ откатывается @Transactional-тестом** (БД — да) — запись из прошлого теста
  резала «свежий» ключ следующего (3 строки вместо 4); `@AfterEach`-`clear()` обязателен
  (прецедент: `MessageIdempotencyIntegrationTest`). Тесты: 2 юнита (replay без save/broadcast;
  put по ключу) + 3 интеграции (тот же ключ = тот же id + 1 строка; разные/нулевые ключи =
  отдельные строки; один ключ двух авторов независим) — 263 зелёных.

- **Re-seed voice.state при заходе в комнату (2026-09-17, ДЕПЛОЙ ПАРНЫЙ со space-client).**
  Живой репорт: бейдж мьюта сидящего в комнате участника ПРОПАДАЛ у заходящего — voice.state
  push-only, у клиента, не видевшего исходный пуш (или стёршего стор при заходе — клиент раньше
  делал clearAll), нет источника текущих меток. `VoiceMembershipTracker.enter` теперь при
  КАЖДОМ заходе ре-паблишит состояние ВСЕХ участников канала, несущих хоть одну метку
  (`hasAnyState` по 6 множествам, включая implied; публикация вне лока, `publishStateIfChanged(true,…)`
  — безусловная) — сеет заходчику и заодно чинит любого, кто пропустил пуш. Полностью
  поглощает прежний узкий репаблиш mod-метки ВОШЕДШЕГО (тот удалял `republishModeratorState`);
  клиентская парная правка: `joinVoice` чистит только СВОИ метки. Тест
  `joinReseedsTheWholeChannelsMarksForTheJoiner` (+1, 264 зелёных).

- **Storage GC: событийная очистка + ночной сборщик по водяным знакам + раздел Server (#479, 2026-09-17,
  ДЕПЛОЙ ПАРНЫЙ со space-client).** Юзер-решения: чистка НЕ по возрасту, а «по нужде» (когда диск
  заполняется); аттач умирает в момент смерти последней ссылки; VACUUM FULL по умолчанию ON; политики
  редактируются с клиента. **Событийный ярус** (`MessageService.deleteUnreferencedObjects`): при
  delete сообщения / edit-удалении аттачей каждый ключ (key + thumbnailKey) после flush'а проверяется
  `attachmentExists` (форвард, ссылающийся на ключ, держит объект живым) → немедленное
  `AttachmentObjectStore.delete`. **Ночной ярус** (`service/StorageGcService`, прецедент
  RefreshTokenCleanup): замер ФС (`oak.storage.path`, дефолт cwd — диск данных) → sweep сирот
  `attachments/` старше grace (`oak.storage.sweep-grace-days:7`, аплоады в полёте не трогаем; аватары
  НЕ в листинге) → если занято > high (85%): батч-снос СТАРЕЙШИХ сообщений (по
  `min(500, count − min-keep)`, реакции по FK вперёд, батч в TransactionTemplate) до low (50%);
  стопы: low достигнут / пол `oak.storage.min-keep-messages:1000` / **детектор отсутствия прогресса**
  (3 батча без сдвига % — сигнатура Postgres-блоута, DELETE не возвращает место ОС) → WARN про
  VACUUM FULL руками. **VACUUM FULL messages** (`oak.storage.vacuum-full:true`) после чистки, только
  если что-то удалено, только PostgreSQL (H2-скачок), прямым connection (автокоммит — VACUUM нельзя
  в транзакции), с `SET lock_timeout TO '5s'` (занятая таблица → перенос на след. ночь, а не очередь
  за ней; писатели стоят ≤ времени вакуума: на наших объёмах секунды, «минуты» от ~5 млн сообщений).
  **Расписание**: тик `@Scheduled` каждые `oak.storage.gc-tick-interval:PT10M` сверяет minute-of-day
  с настроенным (окно = один тик, «первый тик не раньше») — время правится с клиента без
  пересобытирования. **Политики** — колонки `ServerProfile.storage_{high,low,gc_minute}` (int default;
  null → кодовые дефолты 85/50/210). **REST** `/api/server/storage` (всё под НОВЫМ пермишшеном
  `MANAGE_STORAGE` — В КОНЕЦ enum после VIEW_CHANNEL, бит-стабильность; OWNER держит через
  ADMINISTRATOR): GET (policy + usage + running + lastRun), PUT (валидация high>low, 1..99/0..98,
  minute 0..1439), POST /run → 202, фоновый daemon-поток, повторный → 409; отчёт `StorageGcReport`
  latched in-memory. **ГРАБЛИ ПО ПУТИ**: native-LIKE `attachmentExists` НЕ авто-флашит persistence
  context — перед проверкой явный `messageRepository.flush()` (иначе видит удалённую строку → объект-
  сирота остаётся); SLF4J не умеет `{:.1}` — только `{}` с pre-formatted String.format; @PostMapping
  с телом отвечает 200 — 202 ставится @ResponseStatus'ом; в гейт-тесте «первый зарегистрированный =
  OWNER» — 403-кейсу нужен владелец + обычный участник. `AttachmentObjectStore` (листинг+delete)
  реализуют MinIO (listObjects prefix attachments/) и noop (пустой листинг). Тесты:
  StorageGcServiceTest (5 юнитов: below-high no-op, батчи до low + смерть сирот при живом форварде,
  no-progress стоп, min-keep пол, sweep только старых-без-ссылок) через СЕЙМ `runOnce(trigger,
  DoubleSupplier)` (диск-процент инжектится) + StorageIntegrationTest (3: гейт 403/round-trip/
  ручной прогон с поллингом) + MessageServiceTest += objectStore-мок. 272 зелёных.
  **Итерация 2 (юзер-решения):** тоггл `storage_gc_enabled` (boolean default true; false =
  scheduledTick выходит ДОТ idle-проверки — автоматика не чистит ВООБЩЕ; ручной /run и событийный
  ярус работают как работали), `StoragePolicy`/DTO/PUT += `gcEnabled` (клиентский тоггл гасит поле
  времени); тик-тест verifyNoInteractions. 273 зелёных. **Итерация 3 (тестовое покрытие чистки,
  юзер-вопрос):** событийный ярус закрыт юнитами MessageServiceTest (delete/edit удаляют ТОЛЬКО
  осиротевшие ключи; ключ с живым форвардом живёт), НОВАЯ `StoragePurgeFlowTest` (@SpringBootTest
  H2, `oak.storage.min-keep-messages=0`, диск-процент через package-private сейм) прогоняет ВЕСЬ
  purge-флоу на реальном SQL: батч старейших → FK-чистка реакций → строки → post-batch
  attachmentExists → отчёт «low watermark reached» при пург-каунте. **ГРАБЛЯ DERIVED-ИМЕНИ:**
  `List<Long> findByOrderByIdAsc(Pageable)` спарсился криво (конвертировал сущности, не id —
  runtime ConversionService-фейл, пойман именно H2-тестом) → явный JPQL
  `@Query("select m.id from Message m order by m.id asc") findIdsOldestFirst`. 276 зелёных.
  Полю остаётся: реальный листинг MinIO (listObjects/prefix/lastModified — noop в тестах), реальный
  VACUUM FULL, реальный замер ФС.

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
- **Лог-файл** `logback-spring.xml`: активный файл `${user.home}/.oak-server/oak-server.log` (без PID — сервер single-instance), **append=true** + RollingFileAppender — часовой ролловер `.gz`, retention ≈ сутки (maxHistory 24 × 50MB, totalSizeCap 1GB, cleanHistoryOnStart); рестарт историю БОЛЬШЕ НЕ стирает (раунд логгирования 2026-10-06 перевернул прежнюю «перезапись на старте»-семантику). Scope `!test`, чтобы `mvn test` не писал в dev-лог. Консольный appender — Spring Boot'овский дефолт (цветной, `logging.level.*` из `application-dev.yml` применяются поверх). Архив за окно выгружается `GET /api/server/logs` (MANAGE_STORAGE) вместе с хвостами docker-контейнеров (резолв имён контейнеров — `ContainerLogCollector.resolveContainer`: точное имя, compose-суффикс `-{name}-N` и префикс-суффикс `-{name}`, матящий dev-compose `oak-postgres` и т.п.).

## Грабли и интеграционный контракт

Уроки из пойманных багов (не воспроизводить):

- **`/ws` должен быть `permitAll` в `SecurityConfig`.** Рукопожатие WebSocket — это HTTP-запрос, и оно проходит через Spring Security; JWT при этом ещё нет (он в STOMP-фрейме `CONNECT`, а не в заголовках рукопожатия). Поэтому эндпоинт `/ws` разрешаем явно, а аутентификацию делаем на STOMP-уровне в `AuthChannelInterceptor` (читает JWT из заголовка `CONNECT`). Не «закрывайте» `/ws` требованием аутентификации — рукопожатие упадёт в 401, и WS вообще не поднимется.
- **STOMP native-заголовки чувствительны к регистру** (Spring сопоставляет по точному ключу, а не по case-insensitive-спеке). `AuthChannelInterceptor` поэтому проверяет и `Authorization`, и `authorization`. Клиент (`oak-client` `StompClient`) шлёт `Authorization` с большой буквы — держите регистр согласованно между клиентом и сервером.
- **`Page.getContent()` неизменяем.** `Collections.reverse(page.getContent())` → `UnsupportedOperationException` при ≥2 элементах (при 1 — no-op, поэтому тест с одним сообщением баг пропускает). Копируйте: `new ArrayList<>(...)`. «Последние N по возрастанию» = `DESC + LIMIT` + reverse (ASC + LIMIT дало бы старейшие N, а не новейшие).
- WS-рукопожатие и cross-client доставка **не покрываются `MockMvc`-тестами** (это интеграционный уровень). Для отладки этого уровня поднимите DEBUG у `org.springframework.web.socket` / `org.springframework.messaging.simp` — по умолчанию (с 2026-10-07) они INFO: пинг-индикатор держит STOMP-кадр каждые 10 с на клиента, и DEBUG логировал каждый кадр, замусоривая суточное retention-окно лога мегабайтами пинг-шума.
- **`SessionDisconnectEvent` может прийти >1 раза за сессию** (Spring так и делает — компоненты должны быть идемпотентны к повторным disconnect). Поэтому в `PresenceTracker` удаление ключится по `sessionId` (`sessionToUsername.remove(session)` возвращает null на дубликате → no-op), а не по голому счётчику username — иначе дубль даёт under-count/уход в минус и ложный `OFFLINE`. Граничные решения (0↔1) сериализованы одним локом — это заодно исключает гонку порядка online/offline при одновременном реконнекте одного юзера. Principal на сессии: `StompHeaderAccessor.wrap(event.getMessage()).getUser().getName()` (его кладёт `AuthChannelInterceptor` в `preSend` ещё до публикации события).
- **`setHeartbeatValue` требует `TaskScheduler`** — без него Spring падает с `IllegalArgumentException` при старте брокера. В `WebSocketConfig` планировщик создаётся inline (`ThreadPoolTaskScheduler`, daemon, `initialize()`) и передаётся в `enableSimpleBroker(...).setTaskScheduler(...)`. Heartbeat согласуется с клиентом по STOMP 1.2 (клиент шлёт `heart-beat:10000,10000` в CONNECT); при пропадании трафика в пределах интервала сервер закрывает сессию → `SessionDisconnectEvent` → `PresenceTracker` OFFLINE.
- **Presigned-URL печатаются на `publicEndpoint`**, не на внутреннем `endpoint` — иначе клиент не дойдёт до MinIO. MinIO-SDK: класс `io.minio.GetPresignedObjectUrlArgs`, метод `getPresignedObjectUrl` (возвращает `String`); класса `GetPresignedUrlArgs` **нет** (частая опечатка). В test-профиле MinIO выключен (`oak.minio.enabled=false`), и `NoopAttachmentUrlSigner` (`@ConditionalOnProperty havingValue=false`) подменяет подписчика — иначе контекст без MinIO не поднялся бы.
- **Истёкший access-JWT на STOMP `CONNECT` — теперь обрабатывается клиентом.** `JwtTokenProvider.isValid` возвращает false для истёкших (8 ч, `oak.security.jwt.access-token-expiration`), и `AuthChannelInterceptor` отбрасывает `CONNECT`. Клиент (`oak-client`) при REST 401 silent-refresh'ит access через `POST /api/auth/refresh` (refresh-токен, ротация) и поднимает WS уже со свежим токеном — симптом «Cannot load channels»/STOMP auth-fail более не требует ручного ре-логина (см. `../oak-client/CLAUDE.md`). На сервере без изменений: поведение 401 корректно.
- **`@MessageMapping` сканируется только на `@Controller`-бинах.** `SimpAnnotationMethodMessageHandler.isHandler` проверяет именно `@Controller`; бин с `@Component`/`@Service` метод не найдёт — в логе будет «No matching message handler methods» при SEND на `/app/...`. Ставьте `@Controller` на обработчик сигналинга (`CallSignalingHandler`).
- **Reuse-detection + `@Transactional` rollback.** При переиспользовании отозванного refresh-токена family-revoke (отзыв всей семьи) делается **до** бросания 401. По умолчанию `ResponseStatusException` (это `RuntimeException`) помечает tx rollback-only → family-revoke бы откатился, а в тесте со следующими шагами вылетел бы `UnexpectedRollbackException`. Поэтому `AuthService.refresh` несёт `@Transactional(noRollbackFor = ResponseStatusException.class)` — revoke коммитится, а 401 всё равно возвращается через resolver (от исхода tx не зависит). Не «чините» это убиранием `noRollbackFor`.
- **Чистка refresh-токенов — по `expiresAt`, а не по факту `revoked`.** Отозванный-но-не-истёкший токен нужно хранить до естественного истечения: иначе повторное предъявление украденной копии не найдёт его по хешу, и reuse-detection не сработает. Поэтому `deleteByExpiresAtBefore` (а не `deleteByRevokedTrue`). Рост таблицы всё равно ограничен сроком жизни refresh (30 дней). **Грабля self-invocation (живой баг: чистка НИКОГДА не работала):** планировщик входит в `scheduledPurge()` через CGLIB-прокси, но тот зовёт `purgeExpired()` на `this` — МИМО прокси, `@Transactional` на `purgeExpired` не применяется → `TransactionRequiredException` на каждом прогоне. Существующий тест звал `purgeExpired()` извне (через прокси) и баг не видел. Фикс: `@Transactional` на самом `scheduledPurge()`; тест-регресс — `RefreshTokenCleanupTest` (без `@Transactional`, коммиты чистятся в `@AfterEach` — H2-контекст общий, `AuthFlow` считает строки). Общее правило: `@Scheduled`-метод, вызывающий транзакционный метод того же бина, обязан нести `@Transactional` сам.
- **`/api/auth/refresh` логируется построчно (`[auth-refresh]` в `AuthService.refresh`) — диагностика периодических «Session expired».** Каждый исход — строка в `~/.oak-server/oak-server.log`: `rotated` (INFO: user/family/presentedId/tokenAge/fp/from), `REUSE` (WARN: то же — семья отозвана; `tokenAge` показывает, НАСКОЛЬКО устаревшую копию предъявили: древняя = второй держатель токена, свежая = гонка в полёте), `rejected UNKNOWN/EXPIRED`, `BANNED`. `fp` — 8 символов сырого токена, зеркалится клиентом (`MainController.refreshIfNeeded` логирует тот же fp + HTTP-статус: 401 = сервер реально отказал, иное = транспортный сбой, стёрший живые токены — на сервере невидимо, запрос не дошёл). При разборе инцидента коррелировать два лога по `fp`; поле `from` отличает машины.
- **`ddl-auto: update` НЕ удаляет колонки.** Hibernate при `update` только добавляет новые таблицы/колонки под изменившуюся сущность — он никогда не дропает то, что пропало из модели. Поэтому, когда поле `email` убрали из `User`, в существующей dev-БД колонка `users.email` (с её UNIQUE-констрейнтом) осталась висеть «мёртвой»: приложение её просто не использует, но и не чистит. В тест-профиле это незаметно (`create-drop` — схема пересоздаётся из сущности каждый прогон). Чтобы убрать колонку в реальной БД — ручной `ALTER TABLE users DROP COLUMN email, DROP CONSTRAINT <uniq_email_constraint>`; либо завести миграционный инструмент (Flyway/Liquibase) до перехода на `ddl-auto: none` в проде.
- **Подтверждено смоком: мёртвая `users.email` была `NOT NULL` и блокировала ВСЕ новые регистрации на dev.** Hibernate не вставляет `email` (поля нет в сущности) → NULL → `null value in column "email" violates not-null constraint` → `DataIntegrityViolationException` → `AuthService.register` ловит её в `catch` и выбрасывает 409 «Username already exists» (вводит в заблуждение — имя ни при чём). На H2 (тесты) незаметно (`create-drop`). Симптом: регистрация любого нового имени → 409, при этом логин существующих аккаунтов работает. Минимальный фикс (без потери данных): `ALTER TABLE users ALTER COLUMN email DROP NOT NULL` (или полный `DROP COLUMN` выше).
- **Аватар: сервер байты не терминирует → MIME-проверка только по расширению имени.** `POST /api/users/me/avatar` mint'ит presigned-slot, клиент сам PUT'ит байты в MinIO — сервер не знает ни тип, ни размер файла. Поэтому тип контролируется расширением `fileName` (png/jpg/jpeg → иначе 400), а размер — на клиенте (gate до crop, 16 MB). Не пытаться добавить multipart-приём/MIME-sniff на сервере — это ломает принцип «presigned-URL, без BLOB в PostgreSQL, без терминирования байтов». Upload двухшаговый (slot → PUT → `/confirm`) специально, чтобы привязать `avatarKey` к `User` только после успешного PUT — упавший/отменённый upload не оставляет dangling key. Замена/снятие аватара удаляет старый объект (`delete(oldKey)`), orphan'ы в MinIO не копятся.
- **RBAC — bitmask `Permission` по `ordinal()` стабилен: НЕ переупорядочивать константы.** Бит права = `1L << ordinal()`, persisted как `long permissions` на `Role` (и `allow`/`deny` на `ChannelRoleOverride`). Перестановка/вставка константы выше другой молча перемапит все сохранённые маски. Новые права — **только дописывать в конец** группы. Клиенту отдаём `Set<String>` имён (`Permissions.names`/`fromNames`), не сырую маску — клиент не считает биты.
- **RBAC — `@everyone` НЕ в `user_roles`.** Это built-in роль (`kind=EVERYONE`), сеется `RoleSeeder`; `AuthorizationService.effectiveBase`/`hasInChannel` **всегда** подмешивает её в резолв (не через join). Дефолтные права `@everyone` держат приложение юзабельным (send/read/attach/connect/speak) — иначе после включения RBAC новички ничего не смогут. Сравнение role-id в `hasInChannel` по `getId()` на LAZY-прокси (id не инициализирует прокси → без N+1).
- **RBAC — OWNER это роль, не флаг.** «Владелец» = юзер, держащий built-in роль `OWNER` (`kind=OWNER`, `position=MAX_VALUE/2`, `ADMINISTRATOR`, protected — нельзя удалить/переименовать). Бутстрап: первый зарегистрированный → OWNER (`AuthService.register`, `count()==0`); fallback для dev-БД без владельца — сидер промоутит мин-id юзера. OWNER-цель иммунна (`canActOn` → только сам владелец); OWNER-роль нельзя назначить через `assignRoles` (только transfer). `isOwner(user)` = в `roles` есть `kind==OWNER`.
- **RBAC — principal-асимметрия: REST principal=`UserDetails` (→`getUsername()`), STOMP principal=username-строка (→`Principal.getName()`).** `AuthorizationService` принимает `String username` в обоих путях — не завязываться на тип principal. Чеки — **явные** (`requireServer`/`requireInChannel`/`requireCanActOn` в сервисах, кидают `ResponseStatusException(FORBIDDEN)`), **не** `@PreAuthorize` (method-security выключен, `ROLE_USER` авторитеты не тронуты — права резолвятся из БД, смена роли生效 без перевыпуска JWT).
- **RBAC — ban = `User.banned`, enforced на login + каждый REST + refresh + STOMP CONNECT.** Access-JWT живёт 8 ч, поэтому бан обязан срабатывать **немедленно**, а не при следующем логине: `JwtAuthenticationFilter` после `loadUserByUsername` проверяет `PrincipalUser.isBanned()` и при true шлёт `response.sendError(401,"banned")` + **`return` без `filterChain.doFilter`** (short-circuit — защищённый ресурс не отдаётся; пиггибэк на уже существующий lookup фильтра → 0 доп. запросов на каждый REST). Дополнительно: `AuthService.login` → 403 если banned; `AuthService.refresh` → отзывает всю семью + 401 (под тем же `noRollbackFor`, что reuse-ветка, иначе revoke откатится); `AuthChannelInterceptor.preSend` отбрасывает STOMP CONNECT. Кик — **кооперативный** (`/user/queue/member.kick` с телом `{by, banned}` → клиент сам отключается), т.к. гильдии/membership нет (kick не может «убрать из сервера» — только разорвать текущую сессию); бан — единственное, что предотвращает re-entry.
- **RBAC — banned НЕЛЬЗЯ выразить через `UserDetails.enabled`/`accountNonLocked`.** `AuthService.login` аутентифицируется через `authenticationManager.authenticate(...)`, чей `DaoAuthenticationProvider` проверяет `isEnabled()`/`isAccountNonLocked()` и при false кидает `DisabledException`/`LockedException`; а `login` ловит в catch **только** `BadCredentialsException` → забаненный получал бы **500** вместо чистого 403 «banned». Поэтому `PrincipalUser extends org.springframework.security.core.userdetails.User` несёт `banned` **отдельным полем** (`@JsonIgnore`), а его конструктор зовёт `super(...)` со всеми четырьмя статусами `true` (enabled/accountNonExpired/credentialsNonExpired/accountNonLocked); `JpaUserDetailsService.toUserDetails` строит именно его. Login читает banned **прямо из JPA-сущности** (`userRepository.findByUsername`), а не из principal — т.е. `PrincipalUser` нужен только фильтру и никогда не влияет на статус-чеки Spring. НЕ «оптимизировать» это в `.disabled(user.isBanned())` на UserDetails.
- **RBAC — `ddl-auto: update` создаст `roles`/`user_roles`/`channel_role_overrides` автоматически.** Удаления колонок не будет (грабля выше). `user_roles` без ON DELETE CASCADE → перед `roleRepository.delete` чистим assignments (`userRepository.findUsersByRoleId` → remove из коллекции → save), иначе FK-нарушение. **То же для `channel_role_overrides.role_id`** — при удалении роли с оверрайдом FK нарушается; чистим `findByRole_Id` + `deleteAll` (+ flush перед `roleRepository.delete`, чтобы override-DELETE ушёл раньше role-DELETE). НЕ bulk-`@Query delete` — он оставляет stale-управляемые сущности в persistence-context (в `@Transactional`-тесте с разделяемой tx следующий flush падал `TransientPropertyValueException`); загружать и `deleteAll` корректно чистит context.
- **RBAC — новая NOT NULL-колонка на уже населённой таблице требует `default` в `columnDefinition`.** `User.banned` (primitive `boolean`) → Hibernate генерит `alter table users add column banned boolean not null`. В dev-Postgres с существующими юзерами Postgres это **отвергает** (`column "banned" of relation "users" contains null values`) — Hibernate логирует WARN и **колонку НЕ создаёт**, после чего любой селект `users` (напр. `findFirstByOrderByIdAsc` в `RoleSeeder`) падает `column u1_0.banned does not exist` → старт провален. Фикс: `@Column(columnDefinition = "boolean default false")` → `add column banned boolean default false` проходит, существующие строки back-fill'ятся в `false`. **Общее правило для `ddl-auto=update`: любая новая NOT NULL-колонка на таблице с данными — указывай `default` через `columnDefinition`.** (Новые таблицы `roles`/`channel_role_overrides` — пустые, NOT NULL там ок.)
- **STOMP `@MessageMapping` крутится на `clientInboundChannel` БЕЗ `OpenEntityManagerInView` → ленивые коллекции падают `LazyInitializationException`.** OEiV (`spring.jpa.open-in-view=true`) открывает Hibernate-сессию только на HTTP-запрос; WS-обработчики его не получают. Добавление в `VoiceMembershipHandler.join` чека `AuthorizationService.hasInChannel` → `effectiveBase` итерировал `user.getRoles()` (lazy `PersistentSet`) на потоке без сессии → `Cannot lazily initialize collection 'User.roles' (no session)` на каждом `voice.join` (логировалось как `Unhandled exception from message handler method`; membership не регистрировался). В REST это маскируется OEiV + `@Transactional`-сервисами. **Фикс:** `AuthorizationService.findUser` грузит роли eagerly (`@Query("... left join fetch u.roles")`, `findByUsernameWithRoles`) → резолв прав session-агностичен. Альтернатива — `@Transactional` на самом `@MessageMapping`-методе. Любой будущий STOMP-обработчик, трогающий БД/`AuthorizationService` — либо eager-fetch, либо `@Transactional`.
- **Singleton-сущность (instance identity `ServerProfile`) — адресовать `findFirstByOrderByIdAsc`, НЕ хардкод `findById(1L)`.** Сущность одна на инстанс, но `@GeneratedValue(strategy = IDENTITY)` делегирует id БД-sequence: ручное `profile.setId(1L)` Hibernate всё равно отправляет в INSERT, и если sequence уже ушёл выше 1 (предыдущие удаления/ресид в dev) — строка получит id≠1, и `findById(1L)` в `singleton()` упадёт `IllegalStateException`. `findFirstByOrderByIdAsc()` устойчиво находит единственную строку при любом id. Сидер (`ServerProfileSeeder`) idempotentен через тот же `findFirstByOrderByIdAsc().isPresent()→return`. NOT NULL-колонка `name` здесь безопасна (новая пустая таблица — грабля `columnDefinition default` выше касается уже населённых таблиц).
- **НОВЫЙ per-channel-топик = парная правка regex в `DmSubscriptionInterceptor` (иначе DM-утечка).** Простой брокер НЕ авторизует `SUBSCRIBE` — приватность DM-тем держится только на интерсепторе, чей pattern теперь `^/topic/channel\.(\d+)(?:\.edited|\.deleted|\.reactions)?$`. Топики `.edited`/`.deleted`/`.reactions` (edit/delete/reactions push) добавлены туда синхронно с их появлением в `MessageService`; любой будущий суффикс семейства `/topic/channel.{id}.*` обязан попасть в этот regex, иначе он открыт для перебора id не-участниками. **MockMvc это НЕ ловит** (нет WS-слоя) — только ручной smoke (не-участник подписывается — фрейм должен молча дропаться).
- **`Message.editedAt` — ручная установка, НЕ `@UpdateTimestamp`.** `@UpdateTimestamp` штампует **каждый** UPDATE сущности (а не только реальные правки контента) и рассчитан на непустое значение; `editedAt` же nullable («null = никогда не правилось») и ставится ровно в одном месте — `MessageService.edit`. Сравните `createdAt`: `@CreationTimestamp` + `updatable=false`.
- **`record MessageResponse` — wire-совместимость: новые поля ТОЛЬКО в конец и синхронно с клиентским зеркалом.** Мапперы oak-client (`RestClient`/`StompClient`) НЕ отключают `FAIL_ON_UNKNOWN_PROPERTIES` — старый клиент против нового сервера упадёт на неизвестном поле. Оба зеркала меняются одним проходом (dev-деплой парный), поле `editedAt` дописано последним компонентом в обоих. **Исключение — согласованная ЗАМЕНА (применена для аттачей):** скалярные `attachmentFileName`/`attachmentUrl` (слоты 7–8) заменены списком `attachments` на их месте — законно только потому, что dev-деплой всегда парный и старых клиентов нет; любая такая замена = одновременная правка обоих зеркал + всех literal-JSON тестов + (если поле persisted) миграция колонок.

- **Упоминания парсятся на read-time, БЕЗ БД-колонки.** `MessageService.toResponse` — единая точка: вызывает `MentionParser.parse(content, author, channel)` и для create-broadcast, и для `listByChannel` (history). Mentions НЕ персистятся на `Message` → rename user/role оставляет старые `@` простым текстом (lookup miss), tradeoff задокументирован в `MentionResponse`. `MentionParser.parse` трогает `author.roles` (lazy) и `overrideRepository.findByChannelId` на каждый mention — N+1 в mention-count (`ReadStateService.snapshot` парсит freshest-200 unread per channel), acceptable small-instance; если упрётся — batch-overload `toResponse(List)` с pre-built картами. `@everyone`/`@here` резолвятся по **литералу** через `findByKind(EVERYONE)`, НЕ по name (сидер хранит name буквально `"@everyone"`); non-mentionable роль НЕ consumedит токен (падает на same-named юзера). **Имя роли** валидируется `[A-Za-z0-9_-]{1,32}` в `RoleService` — иначе пробел в имени роли ломает plain-`@token` grammar.
- **Read-state: `countByChannelIdAndIdGreaterThan` + read-floor 0 для «never read».** `lastReadMessageId==null` = ничего не прочитано → readFloor=0 (считаем все id>0). Bootstrap `seedInitialReadStates` в `AuthService.register` ставит lastRead=current-latest каждого канала, чтобы новичок не видел архив как unread. mentionCount аппроксимация по freshest-200 (cap) — старые mentions за пределами окна не считаются (задокументировано в DTO). `ChannelReadState` — новая пустая таблица, `ddl-auto=update` чист (грабля `columnDefinition default` неприменима).

- **`ddl-auto: update` НЕ обновляет enum CHECK-constraint (грабля, поймана smoke'ом по живому Postgres).** При первом создании схемы Hibernate генерирует для `@Enumerated(STRING)` enum-колонок check-constraint `<таблица>_<колонка>_check`, перечисляющий значения enum на момент генерации схемы — напр. `channels_type_check CHECK (type IN ('TEXT','VOICE'))`. Добавление новых значений в enum (`DM`/`GROUP_DM` в `ChannelType`) под `ddl-auto=update` **не пересоздаёт** этот check (update только добавляет таблицы/колонки) → insert нового значения падает `ERROR: new row for relation "channels" violates check constraint "channels_type_check"`. H2-тесты (`create-drop`) регенерируют схему каждый прогон → баг ловится **только smoke'ом по живому Postgres** с существующей схемой. Фикс на существующей БД: `ALTER TABLE channels DROP CONSTRAINT channels_type_check; ALTER TABLE channels ADD CONSTRAINT channels_type_check CHECK (type IN ('TEXT','VOICE','DM','GROUP_DM'));`. Свежая БД (новый Postgres / `create-drop`) регенерируется корректно (со всеми текущими значениями). **Общее правило: при добавлении значений в string-backed enum на уже населённой таблице — вручную обновить Hibernate check-constraint.** Применено к `channels.type` для DM.
- **Приватные каналы (грабли раунда).** (1) **Ordinal-ловушка Permission бьёт по @everyone**: VIEW_CHANNEL обязан быть последним элементом enum, а существующие dev-БД (маска @everyone без нового бита) без ре-гранта делают все каналы невидимыми — `ensureEveryoneViewChannel()` крутится при КАЖДОМ старте как инфраструктура (deny VIEW — только per-channel/per-role). (2) **Слоистая резолюция вместо аккумуляции**: старое `allow |= / deny |=` с глобальным deny-wins не позволяло role/member-allow перебить @everyone-deny; слои (everyone → roles → member) применяются последовательно `(mask | allow) & ~deny` — сохранённый тест-регресс `roleAllowBeatsEveryoneDeny`. (3) **VIEW-мастер-гейт в hasInChannel** — одно место покрывает каждый channel-permission call-site; отдельно гейтить каждый endpoint не нужно (и нельзя забыть). (4) **SUBSCRIBE-гейт TEXT/VOICE — по READ, не по VIEW**: locked-канал виден аутсайдеру (так задумано) и клиент подписывается на его тему — VIEW-гейт пропустил бы live-тела при 403 истории; интерсептор переименован в `ChannelSubscriptionInterceptor` (имя DmSubscriptionInterceptor стало ложным), гейт subscribe-time-only (живая подписка переживает флип прав — см. follow-up-пункт). (5) **Derived-имя репозитория**: суффиксы после свойства (`findByChannelIdWithUser`) Spring парсит как свойство → `PropertyReferenceException` на старте контекста (весь suite падает 119 errors); правильно `findWithUserByChannelId` (`find<Произвольно>By<Свойство>`). (6) **`convertAndSend` ping'а** — телом любой объект; выбран `Long`-timestamp (калька channels.deleted) — новый payload-стиль не заводим. (7) **void-методы Mockito не стабятся `when()`** — `'void' type not allowed here`; только `doThrow(...).when(mock).method(...)`.

## Ссылки

- Блюпринт проекта (стек + архитектура): `../oak.txt`
- Сопряжённый клиент: `../oak-client` (см. его `CLAUDE.md`)
- [x] **Launch-раунд B: контейнеризация + TLS-вход через Caddy + advertised-схема (2026-10-07, код готов, смоук pending; ДЕПЛОЙ ПАРНЫЙ со space-client — wire: `ServerProfileResponse` += 7-е `connectionScheme`; клиентский mirror обновлён тем же раундом; миграция V17 — живой dev-старт применит сам).** Цель: сервер разворачивается `docker compose --profile full up -d` и терминирует TLS. **(1) Конфиг-реструктуризация:** `application.yml` стал общей базой — ВЕСЬ блок `oak.*` + datasource/flyway/jpa/hikari переехали туда из dev (env-плейсхолдеры с localhost-дефолтами не менялись); `application-dev.yml` = только `spring.config.import env/.env` + лог-уровни; `application-prod.yml` НОВЫЙ = (а) **все секреты теряют dev-фолбэки** (`${JWT_SECRET}`, `${POSTGRES_PASSWORD}`, `${MINIO_APP_PASSWORD}`, `${JANUS_ADMIN_SECRET}`, `${TURN_STATIC_AUTH_SECRET}` без дефолтов — контейнер без secrets из env/.env НЕ СТАРТУЕТ, Spring поимённо назовёт недостающую переменную), (б) `server.forward-headers-strategy: native` (Tomcat RemoteIpValve переписывает `getRemoteAddr()` из X-Forwarded-For — LoginRateLimiter per-IP и `[auth-refresh]`-лог за прокси видят реальный клиентский адрес; RemoteIpValve доверяет RFC1918-прокси, docker-сеть 172.16/12 проходит; наружу 8080 всё равно не мапится). **(2) Контейнер oak:** `env/Dockerfile.server` — `eclipse-temurin:17-jre-jammy` + `COPY oak-server.jar` (jar кладётся рядом ФИКС-именем: `mvn -B package -DskipTests && cp target/oak-server-1.0-SNAPSHOT.jar env/oak-server.jar`; сборка в образе отвергнута — слабый VPS платит минуты за maven-образ, runner строится за секунды; тот же Dockerfile+jar провижининг раунда C выложит в /opt/oak); сервис `oak` в compose за профилем `full`, env-переменные собирают внутренние адреса (POSTGRES_HOST=postgres, MINIO_ENDPOINT=http://minio:9000, JANUS_REST_URL=http://janus:8088/janus, JANUS_ADMIN_URL=http://janus:7088/admin) и публичные (`MINIO_PUBLIC_ENDPOINT=https://$OAK_PUBLIC_HOST:$OAK_MINIO_HTTPS_PORT`, `JANUS_PUBLIC_REST_URL`, `TURN_HOST`), **8080 наружу НЕ мапится** (Caddy — единственная HTTP-поверхность), файловый лог (GET /api/server/logs) на volume `oak-server-logs`. `.gitignore`: `env/oak-server.jar`, `env/caddy/certs/`. **(3) Caddy** (`env/caddy/`): `Caddyfile` — сайт `{$OAK_SITE_ADDRESS}` (дефолт https://localhost): `/janus*` → janus:8088 (префикс сохраняется), остальное (REST + /ws Upgrade прозрачно) → oak:8080; отдельный сайт `{$OAK_MINIO_SITE_ADDRESS}` (https://host:9443) → minio:9000 — **MinIO на СВОЁМ порту, НЕ path-префикс**: S3v4-подпись считается по Host+path, а Caddy reverse_proxy по умолчанию ПРОПУСКАЕТ исходный Host (проверено по докам) — подпись, минтованная для https://ip:9443, валидируется MinIO только если он видит тот же Host+path. TLS-режим файла — серт из ФАЙЛОВ (`tls {$OAK_TLS_CERT} {$OAK_TLS_KEY}`): для bare-IP хостов ACME не выдаёт серт; self-signed на 10 лет генерит `gen-self-signed.sh [names…]` (SAN из аргументов, IP:/DNS: авторазличение, печатает SHA-256 fingerprint — значение, которое клиентский TOFU-диалог показывает и пинит; короткоживущий серт ломал бы пины ротацией — потому НЕ `tls internal`). ДОМЕННЫЙ деплой: удалить две `tls`-строки (Caddy сам делает ACME/Let's Encrypt на оба сайта), `OAK_SITE_ADDRESS=https://domain` — переключение выполняет провижининг раунда C. `admin off`. **(4) Серверные правки:** `V17__server_profile_connection_scheme.sql` + `ServerProfile.connectionScheme` (http|https, null=исторический http); `UpdateConnectionInfoRequest` += 3-е поле `@Pattern(regexp="https?")` (append-only wire), `ServerProfileResponse` += 7-е поле (ПАРНЫЙ деплой); `InviteController.buildLink`: схема из профиля, порт печатается ТОЛЬКО при advertised-хосте И не-дефолтном для схемы значении (80/443 опускаются, 8080 при http остаётся), Host-fallback несёт свой порт как раньше — юнит `InviteLinkBuildTest` (5: дефолт/https-443/http-80/нестандартный/host-fallback). **JanusProperties += `publicRestUrl` + `clientRestUrl()`** (blank/null → restUrl): ГЛАВНАЯ развилка раунда — `restUrl` используется и СЕРВЕРОМ (RestClientJanusAdminClient: сессии/kick/poll), и КЛИЕНТОМ (RoomResponse), просто поменять JANUS_REST_URL на https://ip/janus нельзя (сервер ходил бы наружу через Caddy); теперь сервер ходит внутри сети, клиенту отдаётся `clientRestUrl()`. **(5) .env.example:** += `JWT_SECRET` (обязателен для full-профиля; дев-запуск с хоста не требует), OAK_PUBLIC_HOST / OAK_HTTPS_PORT / OAK_MINIO_HTTPS_PORT / OAK_SITE_ADDRESS / OAK_MINIO_SITE_ADDRESS (закометированы, дефолты localhost/443/9443). **Security-проход раунда:** Caddy — единственная публичная HTTP-поверхность (8080 не мапится; периферийные порты 5432/9000/9001/8088/8188/7088 остаются host-mapped для ДЕВ-топологии — в проду их закрывает ufw, раунд C; отмечено в шапке compose); XFF-спуф внутри docker-сети = доверенный периметр своих контейнеров; серт-файлы смонтированы ro; build-login-гейты не менялись (PATCH /api/server/connection по-прежнему MANAGE_SERVER). Тесты: InviteLinkBuildTest (5) + RoomControllerPublicUrlTest (1, properties-override: RoomResponse несёт публичный URL при внутреннем restUrl) + расширенные ServerProfileIntegrationTest (scheme round-trip + ftp-400). — **530 тестов (было 524)**. Смоук-сценарий — в чате сессии (не сюда): full-up на dev → https://localhost TOFU-коннект клиента → аттачи через 9443 + голос через /janus + invite-ссылка https.
  **Смоук-фикс 1 (живой старт, 2026-10-07): `MinioConfig` строил ОДИН клиент на `publicEndpoint`** — в контейнере это `https://localhost:9443`, изнутри сети ведущий в никуда: bucket-create в `@PostConstruct` валил контекст рестарт-циклом (`Failed to connect to localhost/[::1]:9443`). Правка: **ДВА клиента** — `minioOperationsClient` (@Primary, на `endpoint`: bucket/deletes/listings; @Primary ради `ObjectProvider<MinioClient>` в ServerHealthService — провайдер при двух бинах резолвит primary) + `minioPresignClient` (на `publicEndpoint`: SDK подписывает presigned URL по endpoint клиента, потому подпись обязана жить отдельным инстансом); `MinioAttachmentService` разведён по ним. **Смоук-фикс 2 (та же машина): Windows Hyper-V excluded UDP-диапазоны** (`netsh interface ipv4 show excludedportrange protocol=udp`) плавают после перезагрузок и накрыли relay 55000–55050 (порт 55031) — `bind: An attempt was made to access a socket…`; в env/.env диапазон сдвинут на 55100–55200 (окно 55051–58344 свободно). Если после перезагрузки снова упадёт — посмотреть excluded-таблицу и сдвинуть TURN_MIN_PORT/TURN_MAX_PORT (compose/флаги coturn едут из одних переменных). Попутно `gen-self-signed.sh`: MSYS_NO_PATHCONV=1 (Git Bash превращал `-subj /CN=oak` в виндовый путь) + дедуп SAN-аргументов против дефолтных.
  **Смоук-фикс 3 (живой репорт 2026-10-08: видео/аудио «link expired», presigned-URL несли ВНУТРЕННИЙ адрес `http://minio:9000`).** Корень: `MinioAttachmentService` инжектил ДВА MinioClient-бина, но резолв по ИМЕНИ параметра конструктора не дошёл до рантайма (имена параметров не сохранены в байткоде) — при неоднозначности Spring отдал ОБАМ слотам `@Primary` operations-клиент → ВСЕ пресайны подписывались на внутренний endpoint; фото/аватарки это маскировал дисковый кэш клиента (0 сетевых fetch в [media]-activity), видео/аудио — единственные, кто ходит по сети. Лекарь: явный конструктор с `@Qualifier("minioPresignClient")` (заместо @RequiredArgsConstructor) — не зависит от compiler-флагов вообще. ГРАБЛЯ-ПРАВИЛО: два бина одного типа + @Primary = by-name-матчинг НЕ гарантирован без `-parameters`; квалификатор обязателен. Диагностика в пути: [video]/[playback]-логи исходного URL + fresh-ссылки при фейлах (VideoModal, playback.ts recover).
  **Смоук-фикс 4 (живой репорт 2026-10-08: «после перезапуска клиент требует логин заново»).** Симптом-цепочка: клиентский авresume падал «Internal server error» — сервер отдавал **500 на /api/server и /api/users**: `MinioAttachmentService.sign: Failed to connect to localhost/[::1]:9443`. Корень: MinIO SDK при ПЕРВОЙ подписи без заданного региона делает СЕТЕВОЙ зонд региона к endpoint'у — в деве (publicEndpoint http://localhost:9000 с хоста) он незаметно проходил; в контейнере presign-клиент смотрит на публичный https://localhost:9443, недостижимый ИЗНУТРИ compose-сети → каждая подпись с аватаркой = IllegalStateException → 500 → авresume не может пройти → форма логина. Лекарь: `.region("us-east-1")` обоим клиентам в MinioConfig (регион и так светится в старых подписях X-Amz-Credential …/us-east-1/… — это дефолт MinIO); с регионом подпись = чисто локальный HMAC, сети нет вовсе. — Сервер 530/530; деплой: package → cp → up --build oak.

**Синхронизация с провижинингом (раунд C, 2026-10-12):** клиент space-client разворачивает
сервер из своих embedded-шаблонов `space-client/electron/provisioning/files/` — janus/*
(Dockerfile, janus.jcfg, janus.transport.http.jcfg) и Dockerfile.server суть ДОСЛОВНЫЕ
копии `env/`-файлов, docker-compose.yml и caddy/Caddyfile — производные (без profiles и
host-маппингов периферии; tls-строки помечены маркером `provisioning:tls-file`, который
клиент вырезает для доменных ACME-деплоев). **ЛЮБАЯ правка env/* обязана зеркалиться в
space-client/electron/provisioning/files/** (и наоборот) — иначе свежепоставленные серверы
расходятся с dev-топологией. Полумеханизировать синхронизацию (генерация из одного
источника) не стали: файлы расходятся сознательно, различия малочисленны и задокументированы
в их шапках.

**JVM-потолок oak-контейнера синхронизирован назад (2026-10-15).** Живой VPS и
provisioning-шаблон уже несли `JAVA_TOOL_OPTIONS: "-Xmx512m -XX:MaxMetaspaceSize=200m
-Xss512k"` (OOM-фикс старта стека от 2026-10-12, вставлялся только в provisioning-версию);
env/docker-compose.yml отставал — следующий deploy уронил бы потолок. Блок перенесён в
env-версию ДОСЛОВНО (двусторонняя синхронизация правила работает и в обратную сторону).
Снижать heap ниже 512m не стали (юзер-решение: не зажимать).

**nat_1_1_mapping в janus (2026-10-14, смоук-фикс packaged-прогона; СИНХРОНИЗИРОВАНО
в обоих наборах).** Живой VPS-репорт: голос на провиженом сервере умирал «ICE failed for
component 1» серией — janus без nat_1_1_mapping анонсирует КОНТЕЙНЕРНЫЙ IP (172.18.0.x) в
host-кандидатах, нерутабельный с чужих машин (на dev прощалось: клиент и Docker на одном
хосте). Фикс: `janus.jcfg` += `nat_1_1_mapping = "@NAT_1_1_IP@"` (плейсхолдер), Dockerfile
ENTRYPOINT подставляет env NAT_1_1_IP (пустой → строка удаляется, bare docker run = сток),
compose janus.environment += `NAT_1_1_IP: ${TURN_EXTERNAL_IP}` (dev .env несёт LAN-IP —
достижимо через UDP port-proxy). **DEV-ДЕПЛОЙ: `docker compose build janus` обязателен**
(конфиг печётся в образ).

**Смоук-фиксы раунда C (2026-10-13, два живых репорта с провиженого VPS; деплой сервер-only,
wire не менялся — клиентская половина уже умела).** (1) **Новый участник не появлялся в
ростере подключённых клиентов** (юзер зарегал второго юзера по инвайту — у овнера его нет
до перезахода): register не публиковал ничего в `/topic/users` — ростер у каждого клиента
это снапшот login-времени (`GET /api/users`) + live-обновления того топика (аватары/ники), и
новичок выпадал из обоих путей. Фикс: `AuthService.register` → `publishUserAfterCommit`
(`UserSummary.from(saved)` — без сигнера, у свежего юзера аватара нет; AFTER COMMIT —
invites-грабля: in-tx пуш даёт подписчикам рефеч докоммитного состояния) — клиентский
`RosterStore.updateUser` уже аппендит незнакомый id. Тесты: НОВЫЙ `AuthServiceRegisterBroadcastTest`
(3: invite-ветка пушила юзера с ником; owner-bootstrap — тот же единый путь; inside-a-tx пуш
ждёт afterCommit — синхронизация гоняется руками, @Transactional-интеграция коммита не даёт).
(2) **Health-check Server Settings показывал «docker unavailable: Cannot run program docker»
на каждой строке**: docker-фасет (`ServerHealthService.containerSnapshot`) и докер-хвосты
выгрузки логов (`ContainerLogCollector`) зовут CLI хоста — в провиженом деплое oak сам крутится
в контейнере (eclipse-temurin) без docker binary; монтировать `/var/run/docker.sock` в
Spring-контейнер отвергнуто сознательно (доступ к сокету = рут на хосте — недопустимое
расширение поверхности). Фикс: `application-prod.yml` выключает обе фичи
(`oak.health.docker.enabled: false`, `oak.logging.container-logs.enabled: false`) — контейнерные
строки читаются UNKNOWN «docker checks disabled», service-пробы (JDBC/MinIO bucketExists/
Janus admin/TURN TCP) остаются рабочими: они и есть честный ответ контейнерного деплоя.
— **533 теста (было 530)**; mvn test зелёный.
  **Итерация 2 (живой репорт 2026-10-13, тот же VPS; ДЕПЛОЙ ПАРНЫЙ — клиентским зеркалом
  обновлены types.ts/describeHealthEntry).** (а) **Ростер-фикс был неполным**: пуш
  `/topic/users` кормит только display-name-ростер (`GET /api/users`), а ПАНЕЛЬ ЧЛЕНОВ
  (MembersPane) живёт на `memberStore` (`GET /api/members` + contentless-рефеч `/topic/members`)
  — новичок по-прежнему не появлялся у подключённых до их рефеча. Register теперь анонсирует
  юзера в ОБОИХ каналах: `MemberEvent("joined", userId)` на `/topic/members` рядом с
  UserSummary на `/topic/users` (одна after-commit-синхронизация; хелпер `afterCommit(Runnable)`;
  тип "joined" — append-only в union'е клиента, обработчик contentless — клиентской логики
  ноль). (б) **«docker checks disabled» убрана с провода**: юзер-вердикт — надпись нулевой
  информативности, читалась как предупреждение. При выключенном docker-фасете сервер шлёт
  `container: null` (DTO-поле nullable, ранний выход containerSnapshot без холостого
  docker-вызова), клиент рендерит строку пробы без контейнерного хвоста — на проде «up · 1 ms»,
  на dev-машине (oak на хосте) контейнерная грань прежняя (running · healthy). Wire: null в
  существующем поле — старый клиент упал бы на `.container.status` — деплой строго
  сервер→клиент (клиентская половина тем же коммитом). Тесты: broadcast-тест переименован в
  BothLiveRosters (verify обоих пушей), ServerHealthServiceTest.disabled…OmitTheContainerSide
  (null-ассерты), ServerHealthIntegrationTest — JsonPath-read + assertNull (грабля явного
  null). — 533 теста; jar пересобран и засинкан в space-client.

- [x] **Launch-раунд D: CI + релизы + версионная связка с клиентом (2026-10-13, код готов;
  срабатывание workflows — после переезда на GitHub в D1).** **Версии:** pom 1.0-SNAPSHOT →
  **0.1.0** — синхронно package.json space-client (0.x до запуска; 1.0.0 = релиз); РЕЛИЗНЫЙ
  ГЕЙТ: тег vX обязан совпадать с pom-версией И с версией клиента — «клиент vX разворачивает
  сервер vX» by construction (клиент вшивает jar релиза той же версии в установщик).
  **finalName=oak-server** (target/oak-server.jar): фикс-имя артефакта, которое ждут
  env/Dockerfile.server, провижининг клиента и release-workflow — версия больше не ездит
  в имени файла (jar:sync-кандидат обновлён; инструкция в шапке Dockerfile упрощена).
  **Workflows (`.github/workflows/`):** `ci.yml` — push master + PR: temurin 17 + maven-cache
  → `mvn -B -ntp test` (тот же гейт, что локально; H2, без Docker); `release.yml` — push тега
  v*: гейт версии (тег == pom через help:evaluate) → `mvn -B -ntp package` (тесты ВКЛЮЧЕНЫ —
  артефакт релизится, он обязан быть зелёным) → `gh release create <tag> target/oak-server.jar
  --generate-notes --verify-tag` (rerun-tolerant: create || upload --clobber). Релиз сервера
  несёт ТОЛЬКО jar; docker-образы (~530 МБ) к нему докидывает space-client скриптом
  images:publish (клиентский release-workflow качает jar+образы релиза ТОЙ ЖЕ версии —
  полный комплект в установщике). **РЕЛИЗНЫЙ ПРОЦЕСС (порядок):** (1) bump pom.xml сервера +
  package.json клиента на одну версию, коммиты обоих репо; (2) тег v* в oak-server, пуш → CI
  jar в релиз; (3) на dev-машине `npm run images:sync && npm run images:publish -- v*` в
  space-client (gh CLI; либо ручной upload архивов в релиз через web-UI); (4) тег v* в
  space-client, пуш → установщик в релиз клиента. Отсутствие asset'а = понятный фейл
  клиентского workflow. Security: workflows без секретов (встроенный github.token,
  permissions contents:write только в release; форк-PR получают read-only токен — write-ветка
  недостижима для чужих контрибуций); тег пушит только владелец. Гейты раунда: mvn package
  зелёный (533 теста), артефакт target/oak-server.jar.

- [x] **Лицензионная атрибуция редистрибьюции образов (2026-10-14, синхронно space-client).**
  Релизы ЭТОГО репо раздают docker-архивы (images:publish) — в корень добавлен
  `THIRD-PARTY-NOTICES.md` (та же таблица: образ/digest/лицензия/тексты/upstream; тексты
  лицензий лежат в space-client/licenses и едут в его установщик). Source-offer копилефт-
  компонентов (janus GPLv3, minio AGPLv3, temurin GPLv2+CE) = upstream-репо + digest-пин
  манифеста. Релизы jar: собственный код + пермиссивные Maven-зависимости (Apache-2.0/
  EPL-2.0/BSD) — notices внутри JAR-ов.

- [x] **Лицензия репо: AGPL-3.0-only (2026-10-14, юзер-решение; синхронно space-client).** LICENSE в корне (полный текст gnu.org + copyright «OAK project»), <licenses>-блок в pom.xml. Зависимости пермиссивные — выбор свободен; GPL-образы в релизах — агрегация, не встраивание (копилефт на наш код не переходит, см. THIRD-PARTY-NOTICES.md).

- [x] **Capability-скелет: version + capabilities в GET /api/server (2026-10-15, код готов, смоук
  pending; ДЕПЛОЙ ПАРНЫЙ со space-client — wire: `ServerProfileResponse` += 8-е `version` (String,
  "dev" при отсутствии build-info — IDE-ран) + 9-е `capabilities` (List<String>, доктрина — см.
  ServerCapabilities); миграций нет).** Цель (юзер-решение по итогам обсуждения): клиент
  ДЕГРАДИРУЕТСЯ ГРАЦИОЗНО против старого сервера — гейтит UI пост-релизных фич по флагам; в
  версию-таблицы НЕ играем (backport/форк/частичный деплой её бы врали). **Доктрина
  (ServerCapabilities javadoc):** флаг добавляется ТОЛЬКО для фич после 0.1.0 — всё, что в 0.1.0,
  = безусловная БАЗА; отсутствие поля у старого сервера = база (никогда НЕ бэкфорить флаги
  базовых фич — форк без них спрятал бы рабочий UI); флаг однажды добавленный не удаляется и не
  переименовывается. Сейчас FEATURES = List.of(). **Версия**: pom += build-info execution →
  BuildProperties-бин (ObjectProvider в ServerProfileController — отсутствует при распакованном
  IDE-ране → "dev"); тестам безразлично (assert isNotEmpty). Тест: ServerProfileIntegrationTest
  getReturnsSeededDefault += version/capabilities-ассерты. — 533 теста (число не изменилось,
  ассерты расширены). Смоук: Diagnostics клиента показывает «Server version: 0.1.0».

## Проработка фич (2026-10-19 — выкладки сохранены, НИЧЕГО не реализовано)

Три серверные фичи проработаны по юзер-запросу (полные выкладки с фазами и граблями —
`../space-client/CLAUDE.md`, раздел «Проработка фич (2026-10-19)»; здесь — серверные
детали для старта раунда). Оценки: A — M (2 серверные фазы), B — M (2-3), C — серверной
логики почти нет (shell-скрипт + provisioning-шаг в space-client).

- **A. Второй режим очистки диска (аттачменты без сноса сообщений):** новый шаг
  `StorageGcService.runOnce` МЕЖДУ `sweepOrphans` и `purgeBelowLowWatermark` —
  detachAttachments: помечать аттачи старейших сообщений `purgedAt` (AttachmentRef +=
  append-only поле в JSON `messages.attachments`; миграций НЕТ — AttachmentListCodec
  lenient читает старые строки с null) → после коммита посмертный `attachmentExists`
  (форвард, ссылающийся на ключ, держит объект — механика purgeBatch реюзом) →
  `objectStore.delete`. Гнать до low watermark; иммунные исчерпаны → прежний purge
  сообщений. ВАЖНО: `attachmentExists`/`likePattern` при пометке обязан перестать
  матчить ключ ИНАЧЕ orphan-свип следующего прогона удалит объект повторно (безвредно,
  но счётчики соврут) — при реализации выбрать: помеченный реф выпадает из
  exists-паттерна (рекомендация — в LIKE-паттерн ключа добавляется маркер, которого в
  помеченном JSON нет) либо отдельный счётчик. Иммунитет: режим count (аттачи последних
  N сообщений с аттачами по id desc) | age (моложе X часов) — колонки ServerProfile
  рядом с watermark'ами + расширение PUT /api/server/storage (валидация как high/low);
  `StorageGcReport` += detachedAttachments/detachedBytes. Тесты: расширение
  StoragePurgeFlowTest (detach-флоу на H2: пометка+удаление объекта, иммунитет,
  переход к purge при исчерпании, форвард держит объект) + StorageGcServiceTest-юниты
  через существующий seam runOnce(trigger, DoubleSupplier). Клиентская половина
  (tombstone-рендер purgedAt) обязательна — см. space-client.
- **B. Bot API:** миграция `users += bot boolean + bot_owner_id` + таблица
  `bot_tokens` (SHA-256-хеш — RefreshToken-калька; created_at/revoked_at; identity-
  блок V4-рецепта). Авторизация: тот же `Authorization: Bearer` — в
  JwtAuthenticationFilter ПОСЛЕ провалки JWT lookup по хешу (in-memory кэш хешей,
  инвалидация на revoke); бан/removed-гейты фильтра работают as-is. `POST /api/bots
  {username, nickname}` гейт MANAGE_MEMBERS (или OWNER-only — развилка раунда) →
  201 {UserResponse, token-один-раз}; DELETE /api/bots/{id} (revoke + member-remove
  механика); перевыпуск токена PUT. Права бота — ШТАТНЫЕ роли через assignRoles
  (отдельная система прав не нужна; DM-запреты/канальные оверрайды достаются даром).
  Rate limit пер-бот на POST messages: LoginRateLimiter-калька (~30/10 мин → 429).
  STOMP-события as-is: AuthChannelInterceptor резолвит бота тем же хедером;
  ChannelSubscriptionInterceptor гейтит по READ_MESSAGE_HISTORY как всем. Wire:
  `UserSummary`/`MemberResponse` += `bot` (append-only, парный деплой — клиентский
  бейдж BOT). Дока: docs/BOT-API.md + echo-бот на node без зависимостей.
- **C. Бэкап/restore:** серверной Java-логики почти нет — весь механизм = shell-скрипт
  `/opt/oak/scripts/backup.sh` (pg_dump -Fc через docker exec postgres + tar volumes
  oak-postgres-data/oak-minio-data/oak-caddy-{data,config} + конфиг /opt/oak без
  логов; v1 = stop-stack ради консистентности) + cron/systemd-timer, ставимые
  provisioning-шагом space-client. Restore: volume-импорты ДО первого compose up,
  pg_restore после старта postgres до старта oak (START_ORDER уже даёт окно), Flyway
  дотащит схему дампа старой версии (миграции append-only, checksum стабильны —
  restore между версиями безопасен). `PATCH /api/server/connection` на новый
  адрес уже существует (register-шаг провижининга его зовет). ОГРАНИЧЕНИЕ: реставрация
  только на ПУСТУЮ базу (restore идёт до первого старта oak, существующих строк нет);
  `.env` из бэкапа обязателен (JWT/MinIO/Janus/TURN секреты).
