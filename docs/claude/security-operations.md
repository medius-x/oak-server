# Права, безопасность и эксплуатация — решения

## Пермишшен-раунд: RESTRICT_PRIVATE_MESSAGES + ретайр битов
EMBED_LINKS(14)/USE_EXTERNAL_EMOJIS(17) → константы RESERVED_* НА МЕСТЕ; V16 чистит биты из roles+оверрайдов.
RESTRICT_PRIVATE_MESSAGES(30) — единственный «вычитающий» бит: роль отбирает ЛС. Запрет отправки и получения; 1:1 заморожен для обеих сторон; в группе молчит/не добавляется; дружба/звонки/переписка не тронуты. Чек по СЫРЫМ маскам кастомных ролей, не effectiveBase (OR не вычитает); @everyone-копия игнорируется, OWNER/ADMIN иммунны. Wire ПАРНЫЙ: MyPermissions/MemberResponse += dmRestricted.

## Аудит безопасности (раунды 1–7)
- Р1/Р2: `SendDestinationInterceptor` — SEND только в /app/**; env/.env вне репо, SSOT `optional:file:./env/.env[.properties]` (без суффикса Spring молча игнорирует dotfile); Janus/MinIO/TURN-секреты из env (scoped oak_app; TURN-тикет HMAC 1ч).
- Р3: `AttachmentSlotRegistry` — confirm аватара/иконки только своего ключа/минта; ключи сообщений с `attachments/`; POST /api/attachments: ATTACH_FILES + 60/10мин.
- Р4: ensureRoom pin-сверка (squat→503).
- Р5: `DirectContactGate` на call.signal/voice.invite (rate-окна, не-self/блок); dmRestricted в нём НЕТ — звонок не «личная связь». Override-списки под MANAGE_CHANNELS; /api/voice/members VIEW-фильтрован.
- Р6 (ПАРНЫЙ): permMutedByChannel; SpeakVoicePolicy на join; −CONNECT → forceLeave, −SPEAK → permMute; ChannelMyPermissions += stream; STREAM в @everyone + ensureEveryoneStream().
- Р7: LoginRateLimiter (20/10мин + 60/10мин IP, до credential-чека); /api/auth/logout(204) + /logout-all; AFK-видео → forceKick; mod-deafen → полный forceLeave; CORS allowCredentials(false), wildcard для Electron. Клиент: SSRF-гейт, safeStorage. Без кода: content-disposition, server-scoped голос-модерация, логины в /api/users, TLS.

## Логи, healthcheck, пинг
Логи: RollingFileAppender; GlobalExceptionHandler — WARN `[api] METHOD /uri -> status`, advice наследует ResponseEntityExceptionHandler (иначе 405/406→500); GET /api/server/logs (MANAGE_STORAGE) → zip логов+контейнеров. show-sql off; bind-trace ЗАПРЕЩЁН (значения — секреты/хеши); диагноз БД — slow-query PG с log_parameter_max_length=0.
Health: GET /api/server/health (MANAGE_STORAGE) — service-проба своими клиентами + container-грань `docker ps -a` («unhealthy» раньше «healthy» — подстрока). Пинг: /app/ping → эхо /user/queue/ping; нет эха = unsupported.

## Приватные каналы (VIEW_CHANNEL) + enforcement живых сессий
VIEW_CHANNEL в КОНЕЦ enum (ordinal 28). ChannelUserOverride + /api/channels/{id}/overrides/users (GET открыт, PUT/DELETE MANAGE_CHANNELS). Слоистая resolveChannelMask: base→@everyone→роли (deny в слое побеждает)→личная строка; ЗАМЕНА глобального deny-wins (role-allow иначе не бил @everyone-deny). VIEW — мастер-гейт в hasInChannel (без него прочие права=false). GET /api/channels viewer-фильтрован, ChannelResponse += locked per-viewer — ПАРНЫЙ; ensureEveryoneViewChannel() + changed-ping. Топики TEXT/VOICE — READ_MESSAGE_HISTORY, НЕ VIEW (LIVE-тела при 403 истории).
Enforcement: VoiceAccessEnforcer.forceLeave (kick→forceKick→revokeTokens→KICKED); enforceConnect; хуки: мутации оверрайдов/ролей, delete канала, свип UNION трекера и tokenHolders.

## Self mute/deafen + rename канала
Трекер: 4 множества self*/mod*; isMuted=UNION; deafen⇒mute; форс-слой по UNION (unmute под deafen не открывает аудио — как Discord); leave чистит только self, мод-метки переживают выход. Wire ПАРНЫЙ: VoiceControlState += self*/mod* флаги ОБА явно (mod=union∖self неоднозначен). PATCH /api/channels/{id}/name: MANAGE_CHANNELS, 400 на DM (защита чужих ЛС), ответ с per-viewer locked.

## Janus token_auth + pin/secret
token per-visit (POST — ПОЛЕМ ТЕЛА, `?token=` только GET-poll); pin=HMAC(adminSecret,"oak-voice-pin:"+id) обязателен в join; secret только серверу для kick/moderate; apissecret отвергнут. Комнаты создаёт СЕРВЕР. Videoroom 1.1.4: unmoderate НЕТ; audio-mid только из joined; pin: 429/433; kick не блокирует re-entry. RoomResponse(janusUrl,roomId,token,pin) — ПАРНЫЙ; деплой сервер→клиент→build janus→рестарт. Грабли: token_auth требует admin_http=true (иначе exit 1, логи ПУСТЫ). Остатки: display-спуф кикает обоих; revoke не рвёт WebRTC ≤60с.

## Settings + RBAC + instance identity
Self-rename: PATCH /api/users/me/username (CHANGE_NICKNAME; ensure-бит в сидере), занято→409, revoke+issueTokens. КЛЮЧ: JWT subject=username → после rename старые access мертвы до 8ч: свежая пара в ответе, клиент переподнимает STOMP. PATCH /api/server/connection (connectionHost/Port, MANAGE_SERVER) — ПАРНЫЙ.
RBAC-фундамент: каталог Permission bitmask; RoleSeeder (@everyone+OWNER); AuthorizationService (effective/иерархия/оверрайды); enforcement MANAGE_CHANNELS/SEND_MESSAGES/READ_MESSAGE_HISTORY + ban-чек login/CONNECT; /api/channels/{id}/overrides/{roleId} (allow/deny↔bitmask); ATTACH_FILES+CONNECT channel-scoped; DELETE канала чистит зависимости руками (нет CASCADE); ServerProfile singleton (/api/server, MANAGE_SERVER).

## Упоминания + read-state
MentionParser: re-parse на read (без колонки БД); @everyone/@here литералом; роль только mentionable&&CUSTOM; массы под MENTION_EVERYONE; rename старит @-тексты. Read-state: GET /api/read-states + POST /api/channels/{id}/read; register сеет read-states новичку.
Idempotency: idempotencyKey — ретрай тем же ключом возвращает ОРИГИНАЛЬНЫЙ response (чек до гейтов, put после).

## Друзья + DM + ник-модель
DM = reuse Channel (+=DM/GROUP_DM) + ChannelParticipant. Единственная content-ветка — в hasInChannel (membership) ДО ADMINISTRATOR short-circuit: даже OWNER не читает чужое ЛС; SUBSCRIBE брокер не авторизует → интерсептор по membership. Дедуп dmKey=min_max unique-nullable (гонка → re-find). GROUP_DM: owner-only, cap 10, уход owner → earliest-joined. FRIEND двумя строками; accept через UPDATE (delete+insert ломает unique); block — ЕДИНСТВЕННЫЙ гейт DM (Discord-faithful); close = delete (v1). REST: /api/users/me/{relationships,friends,blocks}, /api/channels/{dm,group-dm}, participants, DELETE /api/channels/dm/{id}; DDL: relationships, channel_participants, owner_id/dm_key nullable БЕЗ default.
Ники: username=LOGIN (в UI скрыт); nickname nullable-unique, может совпасть с чужим ЛОГИНОМ (проба ников не раскрывает логины); упоминания и friend-запросы матчат ТОЛЬКО findByNickname. PATCH /api/members/{userId}/nickname (себя — CHANGE_NICKNAME, чужого — MANAGE_NICKNAMES).

## Storage GC
Решения: чистка по нужде (диск), не по возрасту; аттач умирает с последней ссылки (форвард держит); VACUUM FULL ON; политики с клиента. Событийный ярус (delete/edit → проверка ссылок → delete сироты; native-LIKE требует явного flush) + ночной: >85% → батчи старейших сообщений до 50%, стопы low / min-keep 1000 / no-progress. REST /api/server/storage под НОВЫМ битом MANAGE_STORAGE (в конец enum; OWNER через ADMINISTRATOR).

## Открытые пункты
- «Играет в …» (wire ПАРНЫЙ): exe-указание, каталога лого НЕТ (внешние API против self-hosted); in-memory; UserSummary += activity, пуш /topic/users; POST /api/users/me/activity; лимит длины + rate-limit.
- Схлопнуть миграции V3–V5 перед релизом: identity-блок V5 внутрь V4; dev перебазлайн (DROP flyway_schema_history + baseline-on-migrate); ДО сноса `SELECT count(*) FROM discussions` (ADD GENERATED только на пустой колонке). Грабли: mvn clean обязателен (target/classes); taskkill /T /F по 8080 (TaskStop не валит run).
- Enforcement-остатки (осознанные ограничения): слитый чужому токен+pin не закрывается (инсайдер-лик = сам транслирует); фоновые сессии клиента не обрабатывают channels.changed; чужие WS-подписки не снять (простой брокер; лечится refetch).
