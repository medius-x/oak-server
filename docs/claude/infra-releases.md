# Инфраструктура, релизы и миграции — решения

## Контейнеризация, TLS/Caddy (раунд B)

Конфиги: `application.yml` — общая база (`oak.*`, datasource/flyway); dev — импорт `env/.env`; prod — секреты без dev-фолбэков (нет env → не стартует) + `server.forward-headers-strategy: native` (XFF → реальный адрес за прокси). `env/Dockerfile.server`: temurin-17-jre + jar фикс-именем; `oak` за профилем `full`; **8080 наружу НЕ мапится** — Caddy единственная HTTP-поверхность.

Caddy: `{$OAK_SITE_ADDRESS}` — `/janus*` → janus:8088, остальное → oak:8080; MinIO — отдельный сайт на СВОЁМ порту (:9443), НЕ path-префикс: S3v4-подпись по Host+path, Caddy пропускает исходный Host. TLS из файлов (ACME bare-IP не выдаёт); self-signed на 10 лет — `gen-self-signed.sh` (fingerprint для TOFU; НЕ `tls internal` — ротация сломает пины); домен = убрать tls-строки → ACME.

Wire: V17 + `ServerProfile.connectionScheme` (http|https, null=http); `UpdateConnectionInfoRequest` += 3-е поле `@Pattern("https?")`; `ServerProfileResponse` += 7-е (парный деплой). `JanusProperties` += `publicRestUrl`/`clientRestUrl()`: сервер ходит в janus внутри сети (публичный https = наружу через Caddy), клиенту — `clientRestUrl()`.

MinIO — ДВА клиента (`MinioConfig`): `minioOperationsClient` (@Primary, внутренний endpoint) + `minioPresignClient` (publicEndpoint — SDK подписывает по его endpoint); инжект с `@Qualifier`. **ГРАБЛЯ: два бина одного типа + @Primary = by-name НЕ гарантирован без `-parameters`.** Обоим `.region("us-east-1")` — без региона SDK сетевым зондом бьёт в публичный endpoint → 500.

## env ↔ провижининг

`space-client/electron/provisioning/files/`: janus/* и Dockerfile.server — дословные копии env/; compose/Caddyfile — производные (tls-строки под маркером `provisioning:tls-file`). **ЛЮБАЯ правка env/* зеркалится в обе стороны.** JVM-потолок `-Xmx512m -XX:MaxMetaspaceSize=200m -Xss512k` в обоих наборах (ниже не зажимать — юзер-решение). `janus.jcfg` += `nat_1_1_mapping` (иначе контейнерный IP в кандидатах → ICE failed); `compose build janus` обязателен — конфиг печётся в образ. Прод: `oak.health.docker.enabled: false`, `oak.logging.container-logs.enabled: false`; docker.sock НЕ монтировать (сокет = рут).

## Регистрация

`AuthService.register` → after-commit пуш в `/topic/users` (UserSummary) и `/topic/members` (MemberEvent "joined") — in-tx пуш отдаёт докоммитное состояние. Health без docker-фасета: `container: null` (деплой сервер→клиент).

## Релизы и CI (раунд D)

pom → **0.1.0** синхронно с package.json клиента; `finalName=oak-server` → `target/oak-server.jar` (фикс-имя). **Гейт: тег vX == pom == версия клиента** (установщик вшивает jar той же версии). `ci.yml`: `mvn -B -ntp test` (H2); `release.yml` (тег v*): гейт версии → package (тесты вкл.) → `gh release create <tag> target/oak-server.jar`. Серверный релиз — ТОЛЬКО jar; docker-образы докидывает space-client (`images:publish -- v*`). Порядок: (1) bump pom+package.json одной версии, коммиты обоих репо; (2) тег v* в oak-server; (3) images:publish; (4) тег v* в space-client → установщик. Workflows без секретов (contents:write только в release).

## Лицензии

**AGPL-3.0-only** (юзер-решение). `THIRD-PARTY-NOTICES.md`: source-offer копилефт-образов (janus GPLv3, minio AGPLv3, temurin GPLv2+CE) = upstream + digest-пин. Образы в релизах — агрегация: копилефт на код OAK не переходит.

## Capability-механизм (ServerCapabilities)

`ServerProfileResponse` += 8-е `version` ("dev" в IDE-ране) + 9-е `capabilities` (List<String>). Клиент ГРАЦИОЗНО деградирует против старого сервера; в версию-таблицы НЕ играем (юзер-решение). **Доктрина:** флаг только для фич ПОСЛЕ 0.1.0 (0.1.0 = безусловная база); отсутствие поля = база, НЕ бэкфорить флаги базовых фич; флаг не удаляется/не переименовывается. Флаги: `backups`, `server-migration`, `afk-roster`.

## Бэкап + restore сервера

`BackupDumpSql` (byte[] = отказ) + потоковый `splitSqlStatements`. `BackupService`: одна REPEATABLE READ-сессия; 18 таблиц, БЕЗ flyway_schema_history и refresh_tokens (ре-логин); MinIO STORED (gzip 0); manifest.json (flywayVersion, appVersion); single-slot `oak-backup.zip`; сервер НИЧЕГО не хранит — расписание клиентское. ГРАБЛЯ: GZIP над ZipOutputStream закрывает underlying → close()=finish().

REST `/api/server/backup`: POST /run (202/409), GET /status, GET /estimate, GET /file. **Гейт `requireAdministrator` НАМЕРЕННО строже MANAGE_SERVER** — архив несёт ВСЕ переписки (юзер-решение). Кросс-лок `StorageMaintenanceLock` с GC — бэкап деферрит ночную чистку (окно догона × 6), а не съедает.

`RestoreRunner` (@Order(-100), до сидеров): env `oak.restore.file/public-host/public-port/public-scheme`; manifest-гейт — flywayVersion == текущая (грабля V16); статементы в ОДНОЙ транзакции; объекты putObject прощающе; патч server_profile из env ПОСЛЕ накатки; rename `.done`; лог-маркеры `[restore] RESTORE COMPLETED/FAILED` — провижининг грепает по docker logs. compose: `./restore:/restore` + OAK_RESTORE_*.

Deliver-and-delete (юзер: архив удаляется ПОСЛЕ скачивания): полное чтение → deleteIfExists; обрыв хранит для ретрая. Wire: `BackupStatusResponse` += `fileAvailable`, `BackupEstimateResponse` += `availableBytes`; клиент блокирует старт при нехватке места. DELETE `/api/server/backup/file` — идемпотентный 204. ГРАБЛЯ: claim (CAS) /run в ПОТОКЕ ЗАПРОСА — 202 ВСЕГДА running=true.

## Миграция server-to-server

`BackupRunResponse` += 11-е `archiveSha256`. Range/206 в `openArchiveForDelivery(start, length)`: deliver-and-delete по ХВОСТУ (удаление после последнего байта; обрыв хранит — иначе `curl -C -` качает с нуля). `parseRange`: `bytes=A-B | A- | -N`; мусор/multi → 200 FULL (RFC 9110 — игнорировать); за-концом → 416 + `Content-Range: bytes */size`. `MigrationTransferService` — in-memory SINGLE-SLOT (без таблиц; рестарт старого сервера = честный провал): 32B SecureRandom → base64url; в памяти только SHA-256-хеш; MessageDigest.isEqual (constant-time); TTL 2 часа (окно docker/compose); новая выдача вытесняет; revoke идемпотентен; лог — только fingerprint; многоразовый в TTL («одноразовость» = deliver-and-delete архива); begin/endTransfer — слот (429). POST/DELETE `/api/server/backup/migration-transfer` (Bearer + requireAdministrator; `MigrationTransferResponse{token, expiresAt, archiveSha256, archiveBytes}`); GET `/transfer/status` (204/404/401) и `/transfer/file` (Range + слот). permitAll `/api/server/backup/transfer/**` — гейт в контроллере по заголовку **X-Space-Migration-Token**. Capability `server-migration`. ГРАБЛЯ: `bytes */size` в javadoc ломает компиляцию.

## Голос: мёртвая комната + утечка janus-токенов

Диагностика: `scripts/diagnose-voice-room.sh`. `ERROR_NO_SUCH_ROOM=426` (ACK и poll) → `VoiceRoomVanishedException`. Probe-first свип: `probeRoom(channelId)` (сбой читается EXISTS); MISSING → `VoiceAccessEnforcer.evictFromVanishedRoom` (= forceLeave без forceKick: tracker.kick + revokeTokens + KICKED-пуш; join в мёртвую не шлём). Офлайн-TTL: ≥5 последовательных офлайн-тиков (heartbeat 10 с) → `revokeTokensForUser` (все каналы юзера).

## AFK: диагностика + жёлтый статус

`VoiceAfkSweeper.moveToAfk` логирует причину: last active давно при живом клиенте = heartbeat'ы не доходят; 1970 = никогда не репортил. `VoiceAfkStatusPublisher` (`VoiceActivityRegistry` + `AfkController.timeoutOf`; работает и без AFK-канала): `@Scheduled oak.voice.afk-status-interval:PT30S`; `@Transactional` на scheduled-методе; публикует ТОЛЬКО переходы в `/topic/voice.afk` (`VoiceAfkStatusEvent`); выход из голоса = false. Снимок `GET /api/voice/afk` — VIEW-фильтрован как /members. Флаг `afk-roster` — гейтит и фетч, и подписку.
