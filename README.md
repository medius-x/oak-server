# OAK Server

The backend of the OAK self-hosted platform: a single-tenant chat, voice and
media server for one community — text messaging, role-based access control,
voice room signaling and attachment storage, all on your own infrastructure.

> **Users don't have to install this by hand.** The [SPACE desktop client](
> https://github.com/medius-x/space-client) sets the whole stack up from its
> "Create" tab over SSH — Docker, TLS, firewall, secrets — and keeps it updated.
> This README mostly for manual setups and development.

## Stack

| Layer | Technology | Role |
|---|---|---|
| Application | Spring Boot 4 / Java 17 | REST + WebSocket (STOMP) API |
| Database | PostgreSQL | Users, channels, messages, roles |
| Object storage | MinIO | Attachments, avatars, emoji (presigned URLs — bytes never pass through the app) |
| Voice SFU | Janus (videoroom) | Multi-party voice/screen rooms; the server mints per-visit tokens and room pins |
| TURN | coturn | Relay fallback for 1-on-1 calls behind strict NAT |
| TLS entry | Caddy | Single HTTPS surface; Let's Encrypt or pinned self-signed |

## Requirements

- Linux x86_64 (Ubuntu or Debian), Docker + Docker Compose
- 2 GB RAM, 5+ GB free disk

## Running from a release

```bash
mkdir -p /opt/oak && cd /opt/oak
# place docker-compose.yml, env/ and caddy/ from the release archive,
# plus the oak-server.jar; create .env from the documented template
docker compose up -d
```

All secrets (`JWT_SECRET`, database and storage credentials, voice gateway and
TURN secrets) are per-installation values in `.env` — the container refuses to
start without them. Schema migrations (Flyway) apply automatically on boot.

## Running from source

```bash
docker compose -f env/docker-compose.yml up -d   # the peripheral stack
mvn spring-boot:run                              # the app on :8080
mvn test                                         # the full test suite (H2, no Docker)
```

## Design notes

- The server never terminates media: voice flows through Janus, attachments
  upload directly to object storage via presigned URLs
- One installation is one community; authorization is enforced server-side on
  every REST and STOMP frame — the client only hides what the server refuses
- Wire contracts are append-only: releases ship in pairs (the client carries
  the matching server jar), yet a newer client keeps working against an older
  server — post-release features are advertised as capability flags and stay
  hidden where the server does not report them

## License

AGPL-3.0-only — see [LICENSE](LICENSE). The Docker images the client bundles
for setup are attributed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
