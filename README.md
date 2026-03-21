# pickup-sports-api

REST API for [Pickup Sports](https://pickupsports.app), a platform for creating and joining public pickup sports sessions.

## Tech stack

- Java 25 + Spring Boot 4.0 (Gradle)
- PostgreSQL + PostGIS (geospatial queries)
- Flyway (schema migrations)
- JJWT 0.12 (JWT auth, 30-day tokens)
- Resend (transactional email for magic link auth and cancellation notifications)
- Bucket4j (in-memory rate limiting)
- Sentry (error tracking)
- Deployed on Railway EU West (Frankfurt)

## Prerequisites

- Java 25
- PostgreSQL with the PostGIS extension enabled
- A [Resend](https://resend.com) account and API key

## Running locally

```bash
./gradlew bootRun
```

The server starts on port `8080`. Flyway runs migrations automatically on startup.

## Configuration

Set the following environment variables (or override in `src/main/resources/application.yml`):

| Variable | Description |
|----------|-------------|
| `DB_URL` | PostgreSQL JDBC URL, e.g. `jdbc:postgresql://localhost:5432/pickupsports` |
| `DB_USERNAME` | Database username |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Secret key for signing JWTs (use a long random string) |
| `RESEND_API_KEY` | Resend API key for sending emails |
| `APP_BASE_URL` | Frontend base URL used to build magic link URLs, e.g. `https://pickupsports.app` |
| `SENTRY_DSN` | Sentry DSN for error tracking (optional) |

## Database setup

Create a PostgreSQL database and enable PostGIS:

```sql
CREATE DATABASE pickupsports;
\c pickupsports
CREATE EXTENSION postgis;
```

Flyway applies all migrations in `src/main/resources/db/migration/` on startup. No manual setup needed beyond the extension.

## Running tests

```bash
./gradlew test
```

Tests use Testcontainers so Docker must be running. A PostGIS-enabled container is spun up automatically; no local database is required for tests.

## Project structure

```
src/main/java/com/pickupsports/
├── auth/           # Magic link auth, JWT, email
├── session/        # Sessions, participants, jobs
│   ├── controller/
│   ├── domain/
│   ├── job/        # @Scheduled jobs (session completion, reminders)
│   ├── repository/
│   └── service/
└── user/           # User profiles, sport profiles
```

## API

Base path: `/api/v1`. Full API reference is in [`../docs/api-design.md`](../docs/api-design.md).

Key endpoints:

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| `POST` | `/auth/request-login` | No | Send magic link email |
| `POST` | `/auth/confirm` | No | Confirm token, receive JWT |
| `GET` | `/sessions` | No | Discover nearby sessions |
| `POST` | `/sessions` | Required | Create a session |
| `GET` | `/sessions/{id}` | No | Session detail |
| `PUT` | `/sessions/{id}` | Required (host) | Edit a session |
| `DELETE` | `/sessions/{id}` | Required (host) | Cancel a session |
| `POST` | `/sessions/{id}/join` | Required | Join a session |
| `POST` | `/sessions/{id}/guest-join` | No | Join as guest (no account) |
| `POST` | `/sessions/{id}/leave` | No | Leave a session |
| `GET` | `/users/me` | Required | Get own profile |
| `PUT` | `/users/me` | Required | Update own profile |
| `DELETE` | `/users/me` | Required | Delete account (GDPR) |

## Rate limiting

| Endpoint | Limit |
|----------|-------|
| `POST /auth/request-login` | 5 requests per email per hour |
| `POST /sessions/{id}/guest-join` | 20 requests per IP per hour |
| All other endpoints | 200 requests per IP per minute |

## Background jobs

| Job | Schedule | Description |
|-----|----------|-------------|
| Session completion | Every 15 min | Marks sessions with a past `end_time` as `completed` |
| Session reminders | Every 15 min | Sends reminder emails 24h before sessions start |
