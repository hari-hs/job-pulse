# Job Pulse

[![CI](https://github.com/hari-hs/job-pulse/actions/workflows/ci.yml/badge.svg)](https://github.com/hari-hs/job-pulse/actions/workflows/ci.yml)

A job application tracker: log applications, move them through a status
pipeline (applied → phone screen → onsite → offer / rejected), set
follow-up reminders that actually email you, and see aggregate stats —
response rate, status breakdown, applications-per-week — on a dashboard.

A from-scratch Spring Boot + React project, built to mirror a real
production deployment (Postgres, scheduled background jobs, transactional
email, containerized services) while running entirely free and local.
`DESIGN.md` has the full architecture and the reasoning behind the major
decisions — auth strategy, schema design, the idempotent reminder
scheduler, and how each piece of local infrastructure maps to its AWS
equivalent for a future deployment.

## Features

- **Auth** — email/password registration and login, JWT-based sessions,
  BCrypt password hashing
- **Applications** — full CRUD, scoped per user, with search, filtering
  (status / company / date range), and pagination
- **Status history** — every status change is recorded to an append-only
  timeline, not just overwritten
- **Reminders** — schedule a follow-up date on any application; a
  background job polls for due reminders and emails them, with an
  idempotent claim mechanism so a reminder is never sent twice even under
  overlapping job runs
- **Dashboard** — total applications, response rate, status breakdown,
  and a 12-week applications-per-week trend, rendered as hand-built
  charts (no charting library)

## Stack

- **Backend:** Java 21, Spring Boot 4, Spring Security (JWT), Spring Data
  JPA, Flyway migrations, PostgreSQL
- **Frontend:** React 19, Vite, axios
- **Infra:** Docker Compose (Postgres, Mailhog for local email capture,
  and the app itself — backend and frontend both run containerized)

## Quick start (Docker)

One command, nothing else required beyond Docker:

```bash
docker compose up --build
```

Then open:

- **`http://localhost:5173`** — the app
- `http://localhost:8025` — Mailhog's inbox (reminder emails land here)
- `http://localhost:8080/actuator/health` — backend health check

`docker compose down` stops everything; add `-v` to also wipe the
database volume.

## Quick start (native, for active development)

Gets hot-reload on both ends instead of a container rebuild per change.

```bash
docker compose up -d postgres mailhog       # just the infra

cd backend && ./mvnw spring-boot:run        # terminal 2 — http://localhost:8080

cd frontend && npm install && npm run dev   # terminal 3 — http://localhost:5173
```

The frontend's Vite dev server proxies `/api/*` to `localhost:8080`
(see `vite.config.js`), so the same frontend code runs unchanged whether
it's served by Vite or by the containerized nginx build.

## Project layout

```
backend/    Spring Boot API (Java 21, feature-packaged: auth/, application/, reminder/, dashboard/, email/)
frontend/   React + Vite SPA
DESIGN.md   Architecture, schema, and decision log
```

## Security note

Every credential in this repo (Postgres password, JWT signing secret,
Mailhog's total absence of auth) is a hardcoded local-dev default,
intentionally — see `DESIGN.md` §12 for how each one maps to a real AWS
service and what actually changes to deploy for real. None of it is
meant to run anywhere but a developer's own machine as-is.
