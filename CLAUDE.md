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
| REST API | **Spring Web** | Регистрация, логин, списки серверов/каналов |
| Real-time сигналинг и чат | **Spring WebSocket** | WSS-соединения, обрабатываются in-memory (стандартные Java-коллекции для connected users) |
| Безопасность | **Spring Security + JWT** | Защита и REST, и WebSocket endpoint'ов |
| ORM | **Spring Data JPA / Hibernate** | Доступ к PostgreSQL |
| БД | **PostgreSQL** | Users, Guilds/Servers, Channels, текстовые сообщения |
| Object storage | **MinIO** | Медиа, изображения, вложения (клиент грузит напрямую по presigned URL) |
| SFU (опционально, оркестрация) | **Janus** | 3+ участника: голос/видео/шаринг экрана. Backend общается с Janus по HTTP REST |
| TURN (внешний) | **Coturn** | P2P-fallback для звонков 1-на-1. Лёгкий, самостоятельный |

## Текущее состояние

Проект только что инициализирован: `org.example:oak-server`, Java 17, Maven.
В репозитории только шаблонный `src/main/java/oak/server/App.java` ("Hello World") и тест-заглушка.
**Spring Boot и остальные зависимости ещё не подключены в `pom.xml`** (сейчас только JUnit 3.8.1 test-scope).

## Архитектура и потоки связи (роль сервера)

1. **Auth & REST** — `JavaFX → Spring Boot REST (HTTPS/JWT) → PostgreSQL`.
   Сервер валидирует учётные данные, выдаёт JWT, отдаёт списки серверов/каналов.

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

Сущности (Hibernate/JPA), исходя из блюпринта:

- **User** — учётная запись, учётные данные, профиль.
- **Guild / Server** — сообщество (сервер) с набором каналов.
- **Channel** — канал внутри сервера (текстовый / голосовой).
- **Message** — текстовое сообщение (с ссылкой на вложение в MinIO, если есть).

> Структуру таблиц уточнять по мере реализации; выше — доменная область из блюпринта.

## Предлагаемая структура пакетов (roadmap)

Текущий корень: `oak.server`. По мере реализации:

```
oak.server
├── App.java                 // @SpringBootApplication, точка входа
├── config/                  // Security (JWT filter), WebSocket config, CORS
├── controller/              // REST-контроллеры (auth, guilds, channels)
├── websocket/               // обработчики Spring WebSocket, in-memory user registry
├── service/                 // бизнес-логика + интеграции (MinIO, Janus)
├── repository/              // Spring Data JPA репозитории
├── entity/                  // JPA-сущности (User, Guild, Channel, Message)
├── security/                // JWT-провайдер, фильтры, UserDetails
└── dto/                     // запросы/ответы API
```

## Что предстоит реализовать

- [ ] Подключить Spring Boot (starter-web, starter-websocket, starter-security, starter-data-jpa) + postgres-драйвер.
- [ ] Бутстрап `@SpringBootApplication` (замена `App.main`).
- [ ] Слой security: регистрация/логин, выдача и валидация JWT.
- [ ] JPA-сущности и репозитории (User, Guild, Channel, Message).
- [ ] REST API: серверы/каналы/сообщения.
- [ ] WebSocket config с JWT-аутентификацией; in-memory реестр подключённых пользователей.
- [ ] Интеграция с MinIO: генерация presigned-URL.
- [ ] Интеграция с Janus: создание/управление SFU-комнатами через HTTP REST.

## Конвенции разработки

- **Java 17**, сборка Maven (`mvn clean package`, `mvn spring-boot:run`).
- Пакеты: `oak.server.*`. Соответствие директорий и пакетов — обязательно.
- Real-time состояние подключённых пользователей — **только in-memory** (коллекции), не в БД.
- Медиа-файлы — **только** presigned-URL в MinIO, не хранить BLOB'ы в PostgreSQL.
- Сервер **не** терминирует медиа-трафик; только сигналинг + оркестрация Janus/MinIO.
- Секреты (JWT-секрет, креды БД/MinIO/Janus) — через конфиг/переменные окружения, не в коде.
- All endpoints (REST и WebSocket) защищены Spring Security + JWT.

## Ссылки

- Блюпринт проекта (стек + архитектура): `../oak.txt`
- Сопряжённый клиент: `../oak-client` (см. его `CLAUDE.md`)
