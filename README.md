# Job Pulse

A job application tracker: log applications, track their status through a
pipeline (applied → phone screen → onsite → offer/rejected), set follow-up
reminders that actually email you, and see aggregate stats on a dashboard.

Built as a learning project — a from-scratch Spring Boot + React app
designed to mirror a real deployment (Postgres, scheduled jobs, email)
while running entirely free and local. `DESIGN.md` has the full
architecture writeup; `docs/milestone-N.md` walks through what each
milestone added and why, including a few real bugs found and fixed along
the way.

## Stack

- **Backend:** Java 21, Spring Boot 4, Spring Security (JWT), Spring Data
  JPA, Flyway migrations, PostgreSQL
- **Frontend:** React 19, Vite, axios — no router yet, no charting library
  (the dashboard's charts are hand-rolled CSS/HTML)
- **Local infra:** Docker Compose (Postgres, Mailhog for catching reminder
  emails, and — via this milestone — the app itself)

## Quick start (Docker)

One command, nothing else installed required beyond Docker:

```bash
docker compose up --build
```

Then open:

- **`http://localhost:5173`** — the app
- `http://localhost:8025` — Mailhog's inbox (where reminder emails land)
- `http://localhost:8080/actuator/health` — backend health check

`docker compose down` stops everything; add `-v` to also wipe the database
volume (not something to do casually — see `docs/milestone-0.md` for why).

## Quick start (running locally, without Docker for the app itself)

Useful for active development, since it gets hot-reload on both ends.

```bash
docker compose up -d postgres mailhog   # just the infra

cd backend && ./mvnw spring-boot:run    # terminal 2, http://localhost:8080

cd frontend && npm install && npm run dev  # terminal 3, http://localhost:5173
```

The frontend's Vite dev server proxies `/api/*` to `localhost:8080`
(`vite.config.js`), so there's nothing to configure — same code, same
`/api` paths, whether you're running this way or via Docker.

## What's built so far

Each milestone has a matching `docs/milestone-N.md` writeup — a guided
tour of what was added, the reasoning behind non-obvious decisions, and
(for several milestones) a real bug that was found and fixed during
verification, documented rather than swept under the rug:

| # | Milestone |
|---|---|
| M0 | Scaffolding — Spring Boot + React + Postgres via Docker Compose |
| M1 | Auth — register/login, JWT, password hashing |
| M2 | Applications CRUD, backend + frontend |
| M3 | Status history, `PATCH /status`, timeline UI |
| M4 | Reminders (data layer) |
| M5 | Scheduled reminder emails via Mailhog, idempotent claim logic |
| M6 | Dashboard — aggregate stats + charts |
| M7 | Search / filter / pagination |
| M9 | Full Dockerization — this file's own quick-start section |

See `DESIGN.md` §11 for the full planned roadmap, including what's next.

## Security note

Every credential in this repo (Postgres password, JWT signing secret,
Mailhog's total absence of auth) is a hardcoded local-dev default,
intentionally — see `DESIGN.md` §12 for how each one maps to a real AWS
service and what actually changes to deploy for real. None of this is
meant to run anywhere but a developer's own machine as-is.
