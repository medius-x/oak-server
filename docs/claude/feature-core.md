# Ядро и фичи месседжинга — решения

## Базовый стек

`/api/auth/{register,login}`, `/api/users/me`; STOMP — JWT на CONNECT, пуш `/topic/channel.{id}`; MinIO presigned `POST /api/attachments`; Janus `GET /api/channels/{id}/room` → `{janusUrl, roomId}` (клиент REST-ом); `/app/call.signal` → `/user/queue/call.signal`, `from` штампует сервер. Refresh: семьи `familyId`; переиспользование отозванного → отзыв всей семьи (`noRollbackFor` — revoke не откатывается с 401).

## Смена пароля

`POST /api/users/me/password` `{oldPassword, newPassword}`: verify → encode → отзыв ВСЕХ refresh юзера → `issueTokens(newFamilyId)`; ответ `AuthResponse` — текущая сессия живёт, прочие устройства разлогинены. Access stateless 8ч без denylist (осознанно). wrong old → 400, НЕ 401 (401 = expired-access → silent-refresh); new==old → 400.

## Поиск + курсоры (парный)

Матчинг ПОДСТРОКОЙ как Discord, без tsvector (для подстрок pg_trgm честнее); скоуп = канал или весь сервер; фильтры v1 = текст + author. `GET /api/search/messages?q=&channelId=&author=&beforeId=&limit=`: total — константа на страницах; newest-first; limit 25 / кап 50; без channelId — весь сервер (TEXT + READ), DM не возвращаются НИКОМУ; оба пустые → 400; unknown author → total 0, не 404 (нет оракула логинов); ILIKE по content И attachments (имена файлов матчатся). Курсоры: `aroundId` (центрированное окно, чужой id → 404) / `afterId` (ASC); максимум один из трёх → 400; `ReadStateService.ack` МОНОТОННЫЙ — ack частичного окна не откатывает read. Индексы pg_trgm+GIN — миграцией V2, от ~3 символов (клиентский минимум 2 — норма); кириллический кейс-фолд — живой смоук.

## Flyway (сервер-only)

НЕ Liquibase (один Postgres); `ddl-auto: update → validate`; изменение сущности = миграция V(n+1) в том же коммите; сидеры остаются (DATA-бойстрап). V1 = санитайзенный pg_dump (без SET-шапки, `pg_catalog.*`, psql `\restrict` — JDBC их не исполняет); мёртвые колонки чистятся из V1 («чистый V1»); CONCURRENTLY нельзя (прод: `executeInTransaction=false`); dev — baseline-on-migrate v1; test — flyway off, create-drop (осознанно); pom: `flyway-database-postgresql` ЯВНОЙ версией, локстеп с core (Flyway 12 переименовал артефакт). Грабли: src-удаление миграции не чистит `target/classes` → `mvn clean`; таблица под IDENTITY обязана нести identity-блок (H2 слепа).

## Pinned messages (парный)

`Message` += `pinned`/`pinned_by`/`pinned_by_name` (денормалия — «toResponse не ходит в репозитории»)/`pinned_at`. `PUT|DELETE /api/channels/{id}/messages/{messageId}/pin`: гейт MANAGE_MESSAGES, в DM = participant-check (как Discord); повторный пин НЕ переатрибутирует; кап 50 → 400 — ошибка, НЕ вытеснение. `GET …/messages/pinned` ASC; пуш — существующий `.edited`; GC: purge-селектор += `pinned=false`. `MessageResponse` += 13–15-е поля В КОНЕЦ (pinnedBy — строка).

## Discussions/threads (парный)

Тред = `Channel` DISCUSSION (enum В КОНЕЦ; миграция — DROP/ADD CHECK) + `discussions` (`channel_id` UNIQUE, `parent_channel_id`, `origin_message_id` UNIQUE = один тред на сообщение, снапшот исходника). Сообщения треда — обычные messages канала, в историю родителя НЕ попадают. Права ДЕЛЕГИРОВАНЫ родителю (`hasInChannel`: DISCUSSION → parent, один хоп, осиротевший = deny); трекинг `channel_participants`, пост авто-трекает автора. POST `/api/channels/{id}/discussions` (parent не TEXT → 400; origin не в родителе → 404; дубль → 409 — клиент открывает существующий; гейт SEND_MESSAGES), `/mine`, `PUT|DELETE /{id}/track` (204), `DELETE /{id}` = close для всех, пуш ТОЛЬКО REMOVED на родительский `.discussions` (bare-Long на `channel.{id}.deleted` отклонён: id каналов и сообщений в одном пространстве); regex интерцептора расширен на `.discussions`. Скипаются `/api/channels`, read-state (ack → 400), channel-scoped поиск (→ 400), rename (→ 400); удаление родителя сносит треды. Грабли: teardown канала валился на реакциях — bulk `deleteByChannelId` (`@Modifying`!) ПЕРВЫМ; replyCount не декрементируется (принято).

## Размеры медиа (парный)

`AttachmentRef` += `width`/`height` (5/6-е) + `sizeBytes` (7-е), `AttachmentResponse` зеркально: всё знает только ОТПРАВИТЕЛЬ, сервер — passthrough (модель доверия форвардов); цель — layout-бокс до загрузки байтов; JSON `messages.attachments`, legacy = null навсегда; wire append-only.

## Эмодзи — фаза 1: каталог

Сервер первым, НЕ строго парный (новый клиент на старом скрывает по 404). `:name:` — plain text в сообщении, резолвит клиент по каталогу; удаление эмодзи деградирует в plaintext (без FK — трейдофф). Name `[a-z0-9_-]{1,32}` ASCII (парсер клиента — тот же класс); UNIQUE name + file_key. `/api/emojis`: GET всем; POST /slot — гейт MANAGE_EMOJIS, кап 200 → 400, дубль/DIVE → 409, префикс `custom-emojis/` обязателен; DELETE best-effort; пинг bare-Long `/topic/emojis`; `/link`-OR — В КОНТРОЛЛЕРЕ (не расширять GC-ярусы); брошенные слоты — второй sweep-цикл StorageGcService.

## Стикеры — фаза 2

Гейт — MANAGE_EMOJIS (один на «каталог выражений»); отправка — SEND_MESSAGES, НЕ ATTACH_FILES. Стикер ДЕНОРМАЛИЗОВАН: `messages` += `sticker_name`+`sticker_file_key`, без FK, dangling by design — forward verbatim, удаление → клиентский tombstone. Wire: `CreateMessageRequest` += 5-е `stickerId`; `MessageResponse` += 16-е `StickerRef{name, key, url}`; content-or-X: create/edit/forward (edit: стикер неизменяем; subset/кросс-инстанс дропают). V7: UNIQUE(name) пака, имя стикера уникально В ПАКЕ, UNIQUE(file_key); `/api/sticker-packs` (GET паками без N+1; кап 20), `/api/stickers` (префикс `stickers/`); пинг `/topic/stickers`; третий sweep-цикл; ключи НЕ в attachments-JSON — attachment-GC не трогает.

## Эмодзи в реакциях — фаза 3

Кап 20 РАЗНЫХ реакций (400 на 21-ю различную; toggle стоящих свободен). Кастомная едет как `:name:` в ТОМ ЖЕ строковом поле `reaction`; `heart` ≠ `:heart:`; резолвит клиент. Gate-split: СИНТАКСИС (`^:[a-z0-9_-]{1,32}:$` или whitelist) в `resolveReactionTarget` (403 раньше 400); СУЩЕСТВОВАНИЕ (`existsByName` → 400) ТОЛЬКО в add — remove обязан снимать dangling. Кап — ЯВНЫЙ JPQL `count(distinct r.reaction)` (derived считает строки); TOCTOU принят. V8: `reaction` varchar 32→64.

## Инвайт-регистрация (парный)

Непустой инстанс — ТОЛЬКО одноразовые ссылки; пустой регистрирует ПЕРВОГО (OWNER) без секрета. TTL в МИНУТАХ (1..525600), ретроактивен (load+loop — JPQL Duration даёт H2-риск). Использованные удаляются НЕМЕДЛЕННО: `claim` = атомарный guarded DELETE (0 строк → 403; откатывает INSERT юзера); cleanup PT6H. Секрет plaintext; на провод только `secretPreview`. Link собирает СЕРВЕР `http://host:port/#invite=<secret>` из advertised профиля (fallback Host-заголовок — за прокси заполнять advertised). Гейт MANAGE_MEMBERS («кто добавляет членов — тот раздаёт ссылки»); `PUT /api/server/invite-settings` в InviteController (TTL — домен Invites, не MANAGE_SERVER); DELETE неактивной — 204; список — активные. Register по порядку: username 409 → nickname-предчек 409 (username==чужой ник иначе ложный 409) → без секрета 403 → save (DIVE → 409, секрет не расходован) → claim → 403; `RegisterRequest` += `nickname`, `inviteSecret`; пинг `/topic/invites` ТОЛЬКО AFTER COMMIT (иначе рефеч-гонка).

## Категории + voice.invite (парный)

Категории (V12: CHECK += CATEGORY, self-FK `parent_id`, `position` + backfill=id): `Channel` += голый `Long parentId` (wire-Long) + `int position`; `ChannelResponse` += 6/7-е, `CreateChannelRequest` += 3-е. list — (position, id), CATEGORY всегда unlocked; create — валидации parent → 400 + `position = findTop+1` (дефолт 0 не утекает); delete — непустая категория → 400 ПЕРВОЙ проверкой. `PUT /api/channels/order` — ПОЛНЫЙ список не-DM каналов ровно по одному разу (частичный/дубль → 400), плотная перенумерация; пинг channels.changed AFTER COMMIT. `/app/voice.invite`: `from` штампует сервер, channelName резолвит сам; не-VOICE / нет VIEW / blank → ТИХИЙ no-op (перебор ничего не узнает); `/user/queue/voice.invite`; дружбу/присутствие НЕ проверяет (живая карточка без persist).

## Удаление участника, овнерство, AFK (парный)

«Remove from server» (V13 `users.removed`; /kick УДАЛЁН): гейты зеркалом banned (401 / login 403 / refresh-revoke / CONNECT-reject); гейт KICK_MEMBERS + иерархия, овнер 403; ПОРЯДОК: kick-push + голосовой eviction СНАЧАЛА (сессии знают СТАРЫЙ username) → анонимизация `deleted_<id>`/`Deleted-User-<id>`; ник освобождается; авторство не подменяется (`Message.author` live FK, wire — tombstone); `banned()` = banned ∪ removed (`MemberResponse.removed` 8-е поле). Овнерство (V14; одна PENDING на инстанс): offer — isOwner 403 / self 400, новая ТИХО отменяет старую; accept — 403 не-адресат / 409 не-PENDING / 409 «sender is no longer the owner», свап OWNER, `rolesChanged` обоим; заявка умирает с участником; очередь `"/queue/ownership"` БЕЗ /user-префикса (`convertAndSendToUser` добавляет сам); пуши after-commit. AFK (V15 `afk_channel_id` + `afk_timeout_minutes`; без FK): PUT под MANAGE_CHANNELS; sweeper PT30S: протухший → move в AFK + forceKick (порвать Janus; клиент перезапустит /room) + `VoiceControlEvent("MOVED")`; по Discord-фактам (server-mute НЕ снимается юзером; МОЖЕТ в категории): `ChannelResponse` += 8-е `afk`; `AfkVoicePolicy` — каждый в AFK-канале получает МОДЕРАТОРСКИЙ мьют (self-unmute не снимает; rogue-репаблиш не гейтится — кооператив); designation на занятую комнату подавляет сидящих; снятие → `clearModeratorMutes(channelId)` КАЖДОМУ меченому ВКЛЮЧАЯ несидящих; applyOnEnter ПОСЛЕ MOVED-пуша (TCP-порядок).

## Смоук-фикс: DISCUSSION в DM-списке

`DmService.listMine` без фильтра типа (трекинг тредов — те же `channel_participants`) → треды в `GET /api/channels/dm` как «DM с самим собой»; фикс — гейт `type ∈ {DM, GROUP_DM}`.

## Активности ростера («играет в …», 2026-11-10)

Presence-класс (как жёлтая AFK-точка): клиент сам решает и шлёт ЯВНЫЕ переходы
`/app/presence.activity` `{activity: {type, name} | null}` (principal штампует сервер;
type ∈ {playing, listening, watching, streaming, competing}, name trim 1..64; мусор —
тихий no-op). `PresenceActivityTracker` (RosterAfkTracker-идиома с Map-вердиктом вместо
boolean): per-session отчёты, юзерский вердикт = ПЕРВАЯ непустая среди живых сессий
(в порядке прихода — детерминизм); LIVE-карта (снапшот `GET /api/presence/activities`)
и PUBLISHED-карта (дедуп переходов) РАЗДЕЛЕНЫ — dampening (1 с/юзер) глотает только
broadcast, снапшот честен; публикация переходов `/topic/presence.activity`
`PresenceActivityEvent{username, activity|null}` вне лока; последняя сессия юзера
уходит → строка тихо гаснет (OFFLINE рассказывает; клиент при OFFLINE гасит локально).
Ничего не персистится. Флаг presence-activity гейтит клиентский фетч+подписку+репорты; клиентская половина — в space-client/docs/claude/feature-log-recent.md.
