# OAK — Server (Spring Boot Backend)

> Project-level context file. Прочитать перед началом любой работы в `oak-server`.
> Детальные решения вынесены в `docs/claude/` — заходить по необходимости:
> [feature-core.md](docs/claude/feature-core.md) (ядро и фичи месседжинга),
> [security-operations.md](docs/claude/security-operations.md) (права, аудиты безопасности, DM, эксплуатация),
> [infra-releases.md](docs/claude/infra-releases.md) (контейнеризация/TLS, релизы, бэкап, миграции, голос),
> [pitfalls.md](docs/claude/pitfalls.md) (грабли и интеграционный контракт — ОБЯЗАТЕЛЬНО к сведению перед правками auth/RBAC/WS/схемы),
> [research-backlog.md](docs/claude/research-backlog.md) (проработки нереализованного).

## Что это

`oak-server` — бэкенд самохостящегося клона Discord (проект **OAK**). Пара приложений:
**`oak-server`** (этот проект, single-instance Spring Boot) и **`space-client`** (Electron/TS
desktop-клиент, `../space-client`; историческое имя `oak-client`).

Бэкенд обеспечивает аутентификацию, REST API, real-time сигналинг и чат, оркестрирует
внешние сервисы (MinIO, Janus). Медиа-трафик через сервер НЕ проходит — только сигналинг
и URL/roomId; комната в Janus создаётся лениво первым подключившимся клиентом
(`exists`-зонд + `create`), не сервером.

## Стек технологий (целевой)

| Слой | Технология | Назначение |
|------|-----------|------------|
| Core | **Spring Boot** | Основа, single-instance |
| REST | **Spring Web** | Регистрация, логин, списки каналов |
| Real-time | **Spring WebSocket (STOMP)** | WSS, in-memory состояние подключённых |
| Безопасность | **Spring Security + JWT** | Защита REST и WebSocket |
| ORM | **Spring Data JPA / Hibernate** | Доступ к PostgreSQL |
| БД | **PostgreSQL** | Users, Channels, сообщения (схемой владеет Flyway) |
| Object storage | **MinIO** | Медиа/вложения — напрямую клиенту по presigned URL |
| SFU | **Janus** | Голос/видео 3+: сервер отдаёт URL/roomId, клиент сам говорит с Janus по REST |
| TURN | **Coturn** (внешний) | P2P-fallback для звонков 1-на-1 |

## Текущее состояние (перечень подсистем; детали — docs/claude/*.md)

Все пункты реализованы end-to-end, если не сказано иное:

- **Auth**: stateless JWT (HS256, BCrypt; access 8 ч + opaque refresh 30 д хешем в БД);
  refresh-семьи с reuse-detection (повторное предъявление отозванного → авто-отзыв всей
  семьи), ротация, `RefreshTokenCleanup` (`@Scheduled` PT6H); смена пароля self-service;
  инвайт-регистрация (одноразовые коды на непустом инстансе). Открыт: server-side logout.
- **REST каналов/сообщений** + WebSocket (STOMP): `/ws`, broker `/topic`, prefix `/app`;
  JWT на CONNECT (`AuthChannelInterceptor`); heartbeat 10 с; пуш в `/topic/channel.{id}`
  и суффиксы `.edited`/`.deleted`/`.reactions`.
- **Presence** (`PresenceTracker`, `/topic/presence`), **voice-membership**
  (`VoiceMembershipTracker`, `/topic/voice`), **WebRTC-сигналинг 1-на-1**
  (`CallSignalingHandler` → `/user/queue/call.signal`), **Janus-оркестрация** +
  token_auth с per-room pin/secret, принудительный mute/kick; голосовые комнаты
  с лимитом участников (V18: гейт в `/room`, 409 при полноте, байпас-право
  JOIN_FULL_ROOMS — repurpose бита 14); админский выбор видеокодека комнат
  (V24: `server_profile.video_codec`, NULL=`vp8`; `GET/PATCH /api/server/video`,
  whitelist vp8/vp9/h264; `ensureRoom` передаёт `videocodec` при СОЗДАНИИ —
  живая комната живёт со своим кодеком до опустеет, убивать заполненные ради
  переключения нельзя).
- **MinIO-вложения**: presigned PUT/GET, переподписка `GET /api/attachments/link?key=`
  (стримовое воспроизведение переживает часовой URL); мультивложения 0..10
  (JSON-колонка `messages.attachments`, `AttachmentListCodec`); видео-превью —
  клиентская генерация (`thumbnailKey`/`durationMillis` пассажирами).
- **RBAC**: глобальные per-instance роли, bitmask `Permission` (~28 прав), `@everyone`/`OWNER`
  built-ins, channel-overrides (слои everyone→roles→member), приватные каналы
  (VIEW_CHANNEL + пер-пользовательские оверрайды), kick/ban, иерархия `canActOn`,
  голосовой mute/deafen/kick end-to-end.
- **Чат-фичи**: упоминания @user/@role/@everyone/@here (read-time парсинг), read-state
  (unread/mention-счётчики, монотонный ack), edit/delete (Discord-faithful гейты),
  emoji-реакции (whitelist slug'ов), reply/forward (живые цитаты, анти-спуфинг
  `forward_trusted`, subset-forward), кастомные эмодзи + стикеры с паками, pinned
  messages + discussions/threads, DM + групповые DM + друзья, поиск по сообщениям
  (ILIKE + pg_trgm/GIN, курсоры beforeId/aroundId/afterId).
- **Безопасность**: серия аудит-раундов — запрет STOMP SEND в брокерские топики,
  DirectContactGate (запрет ЛС по роли), Janus enforcement, SPEAK enforcement,
  REST/MinIO scoped-ключи и квоты, read-гейты метаданных, получатели личных пушей.
- **IGDB-интеграция** (round 1, V19; SGDB-значки V23): Twitch-ключи + SGDB-ключ (значки игр, write-only, /api/server/sgdb) в Server Settings (`/api/server/igdb`,
  MANAGE_SERVER, секрет write-only), токен client_credentials в памяти с автореминтом;
  прокси к api.igdb.com (сервер-only, без CORS у IGDB); матчинг exe→игра: Steam appid →
  fuzzy search по нормализованным именам (точное совпадение / candidates + confirm);
  кэш `igdb_games` + `igdb_match_cache`; `/api/games/*` — аутентифицированным,
  30/мин/юзер, Apicalypse-санитайз; capability `igdb-games` УСЛОВНЫЙ (только при
  настроенных ключах). Решения — security-operations.md.
- **Инфраструктура**: Flyway владеет схемой (`ddl-auto: validate`, изменения — только
  `V*.sql` в том же коммите); логгирование (rolling, часовой rollover, retention ≈ сутки,
  `GET /api/server/logs`); healthcheck периферии; capability-скелет (`GET /api/server`
  → version+capabilities; НОВЫЕ серверные фичи — с kebab-case флагом, клиент
  гейтит UI по `ServerCapabilities.FEATURES`); контейнеризация + TLS через Caddy;
  бэкап/restore при провижининге; миграция server-to-server (Range/206 + SHA-256 +
  одноразовый токен); CI + релизы + версионная связка с клиентом (парные релизы —
  одинаковый номер версии с space-client).
- **Периферия** (`env/docker-compose.yml`): postgres, minio, janus, coturn.
- Лицензия: **AGPL-3.0-only** (синхронно с space-client); атрибуция образов —
  THIRD-PARTY-NOTICES.md.

## Архитектура и потоки связи (роль сервера)

1. **Auth & REST** — `клиент → Spring Boot REST (HTTPS/JWT) → PostgreSQL`.
2. **Загрузка файлов** — сервер брокер presigned-URL: `клиент просит URL → Spring Boot
   генерирует через MinIO → отдаёт`; файл через сервер НЕ идёт.
3. **Real-time текст и сигналинг 1-на-1** — `клиент ↔ Spring Boot WebSocket (WSS/JWT)`;
   состояние подключённых in-memory; маршрутизация сообщений чата и SDP/ICE.
4. **Голос/видео 3+ и шаринг** — `GET /api/channels/{id}/room → {janusRestUrl, roomId}` —
   клиент сам создаёт комнату в Janus и говорит с ним по REST long-poll; SRTP течёт
   мимо сервера.
5. **Звонки 1-на-1** — сервер только релеит сигналинг; медиа P2P, NAT-fallback через
   внешний Coturn (TURN-сервер бэкендом не управляется).

## Предметная модель (PostgreSQL)

Инстанс OAK = сервер; сущности привязаны к инстансу напрямую (single-tenant; «список
серверов» в клиенте — это несколько физических инстансов, инстансы друг о друге не знают).

- **User** — учётка/профиль; `@ManyToMany Role` (`user_roles`); `banned`.
- **Channel** — текстовый/голосовой/DM/group-DM канал.
- **Message** — сообщение (контент + JSON-вложения + reply/forward-поля).
- **Role** — глобальная роль (bitmask permissions, position-иерархия, kind
  EVERYONE/OWNER/CUSTOM); **ChannelRoleOverride** — per-канальный allow/deny.
- **ServerProfile** — singleton-строка: имя/иконка инстанса, watermark'ы хранения, Twitch-ключи IGDB.
- **IgdbGame / IgdbMatchCache** — кэш IGDB: карточки игр + ключи матчинга (V19).
- Схема — по миграциям Flyway (`src/main/resources/db/migration/`).

## Структура пакетов

```
oak.server
├── App.java                 // @SpringBootApplication
├── config/                  // Security (JWT filter), WebSocket, CORS, MinIO, Turn
├── controller/              // REST-контроллеры
├── websocket/               // STOMP-обработчики, трекеры (Presence, VoiceMembership)
├── service/                 // бизнес-логика + интеграции (MinIO, Janus), сидеры
├── repository/              // Spring Data JPA
├── entity/                  // JPA-сущности
├── security/                // JWT-провайдер, фильтры, UserDetails
└── dto/                     // запросы/ответы API
```

## Конвенции разработки

- **Объём этого файла — не более 28 КБ** (юзер-правило): детали живут в
  `docs/claude/*.md` (каждый тоже ≤28 КБ). При разрастании — выносить в сателлит
  и оставлять ссылку; журнальный стиль записей (даты, статусы смоуков, счётчики
  тестов, имена тестировщиков) не накапливать — сразу решения без шума.
- **Java 17**, Maven (`mvn clean package`, `mvn spring-boot:run`); пакеты `oak.server.*`.
- **Lombok**: `@RequiredArgsConstructor` (DI), `@Getter/@Setter/@NoArgsConstructor(PROTECTED)`
  на сущностях, `@Slf4j`. На сущностях БЕЗ `@Data`/`@ToString`/`@EqualsAndHashCode`.
- **Тесты**: `@SpringBootTest + MockMvc` на профиле `test` (in-memory H2, без Docker);
  `mvn test` самодостаточен. Гейт — все тесты зелёные после каждой фазы.
- Real-time состояние подключённых — **только in-memory**, не в БД.
- Медиа — **только** presigned-URL (MinIO), не BLOB в PostgreSQL; сервер не терминирует
  байты и не валидирует типы (правило presigned-URL).
- Секреты — через конфиг/окружение, не в коде.
- Все эндпоинты (REST и WS) под Spring Security + JWT.
- **Схема**: изменения сущности = миграция `V(n+1)` в том же коммите (Flyway validate —
  страховщик дрейфа); сидеры — DATA-бойстрап, не схема.
- **Лог-файл** `logback-spring.xml`: `~/.oak-server/oak-server.log`, append + часовой
  rollover `.gz`, retention ≈ сутки (maxHistory 24 × 50MB, totalSizeCap 1GB); scope
  `!test`. Архив за окно — `GET /api/server/logs` (MANAGE_STORAGE) с хвостами
  docker-контейнеров.
- **Парность деплоя с клиентом**: wire-изменения (новые поля record'ов, замены,
  STOMP-топики) = синхронная правка зеркала в space-client + парный релиз одной версии.
  Новые серверные возможности — через capability-флаги (`ServerCapabilities.FEATURES`),
  чтобы старый клиент деградировал gracefully.

## Открытые пункты (backlog)

- **Server-side logout**: `POST /api/auth/logout` — отзыв refresh текущей сессии
  (идемпотентный 204); опционально `logout-all` (вся семья = «выйти со всех устройств»).
- **Схлопнуть миграции V3–V5 в чистый набор ПЕРЕД УПАКОВКОЙ** (V5 существует только
  потому, что V3/V4 уже исполнялись на dev).

Реализовано ранее (в списке не держать): remote provisioning обеих фаз (SSH-пайплайн
round C — см. space-client docs/claude/launch-rounds.md), активность «Играет в …».

Детали и историю решений — `docs/claude/{feature-core,security-operations,infra-releases,research-backlog}.md`.
