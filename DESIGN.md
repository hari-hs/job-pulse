# Job Pulse — System Design

_Design reference for the Job Pulse job-application tracker. No implementation yet — this document captures architecture and decisions agreed on before coding begins._

---

## 1. Functional Requirements

**Core (MVP)**
- User can register and log in (email + password)
- User can create, read, update, delete job applications (company, role, status, applied date, job URL, location, notes)
- Application has a status pipeline: `APPLIED → OA → PHONE_SCREEN → ONSITE → OFFER / REJECTED / WITHDRAWN`
- Every status change is recorded as a history entry (timeline view per application)
- User can set a reminder/follow-up date on an application
- System sends an email when a reminder is due
- Dashboard shows aggregate stats: total applications, breakdown by status, response rate, applications-per-week trend

**Secondary (post-MVP, but designed for from day one)**
- Search / filter / sort applications (by status, company, date range)
- Pagination for large application lists
- Tags/labels (e.g., "dream company", "referral")
- Attach a resume/cover letter version per application (future S3 use case)
- Auto-reminder rule: "if no status change in N days, remind me"

**Explicitly out of scope for now:** team/collaboration features, browser extension for auto-import, multi-tenant orgs.

---

## 2. Non-Functional Requirements

| Category | Requirement | Why it matters here |
|---|---|---|
| **Security** | Passwords hashed (BCrypt), JWT-based stateless auth, row-level ownership (user A can never read user B's data), input validation at API boundary | This is the #1 thing interviewers probe on a personal-data app |
| **Scalability** | Stateless backend (no server-side session) so we could run N instances behind a load balancer | Sets us up cleanly for AWS later without a rewrite |
| **Performance** | Indexed queries on `user_id`, `status`, `applied_date`; pagination on list endpoints | Job seekers can accumulate hundreds of applications |
| **Reliability** | Scheduled reminder job must be idempotent — a reminder is never emailed twice, even if the job overlaps or the app restarts mid-run | Trickiest correctness issue in the whole system |
| **Observability** | Structured logging, Spring Actuator health checks, request/error logging | Needed to debug scheduled jobs which have no human watching them fire |
| **Maintainability** | Layered/clean architecture, DTOs at the API boundary (never expose JPA entities directly), dependency inversion for external services (email) | Keeps the codebase interview-defensible and swap-friendly (SMTP → SES) |
| **Portability** | 12-factor config (env vars/profiles), Docker Compose for local dev, no AWS SDK calls hardcoded into business logic | Local-free-first, AWS-later without touching core logic |
| **Testability** | Service layer testable without Spring context; repository layer tested with Testcontainers Postgres | Avoids "works on my machine" schema drift bugs |

---

## 3. High-Level Architecture

```
                    ┌─────────────────────┐
                    │   React SPA (SPA)    │
                    │  (Vite, Axios, JWT   │
                    │   stored in memory)  │
                    └──────────┬───────────┘
                               │ HTTPS / REST + JSON
                               ▼
                    ┌─────────────────────┐
                    │   Spring Boot API    │
                    │ ┌─────────────────┐ │
                    │ │ Controller layer│ │  ← REST, DTOs, validation
                    │ ├─────────────────┤ │
                    │ │  Service layer  │ │  ← business rules, orchestration
                    │ ├─────────────────┤ │
                    │ │Repository layer │ │  ← Spring Data JPA
                    │ ├─────────────────┤ │
                    │ │  Domain entities│ │
                    │ └─────────────────┘ │
                    │  + JWT filter chain  │
                    │  + Scheduler thread  │
                    └───┬──────────────┬───┘
                        │              │
                        ▼              ▼
              ┌──────────────┐  ┌──────────────────┐
              │  PostgreSQL   │  │  Email Sender    │
              │ (Docker local │  │ (interface)      │
              │  → RDS later) │  │ local: SMTP/Mailhog│
              └──────────────┘  │ prod: SES        │
                                └──────────────────┘
```

**Key decision: layered + feature-packaged.** Not split purely by technical layer (`controllers/`, `services/`, `repositories/` as top-level packages) — that scales badly. Instead each *feature* (auth, applications, reminders) is a vertical slice that internally follows Controller → Service → Repository → Entity. Dependencies point inward: controllers depend on services, services depend on repository interfaces, never the reverse. This is clean architecture applied pragmatically, not full hexagonal/ports-and-adapters, which would be over-engineering for this project's size.

**Key decision: DTOs at the boundary.** Controllers never accept or return JPA entities directly. Prevents accidental exposure of fields (like `password_hash`), decouples the API contract from the DB schema, and avoids Hibernate lazy-loading/serialization footguns.

---

## 4. Database Schema

| Table | Purpose |
|---|---|
| `users` | Account identity and credentials |
| `job_applications` | The core tracked entity |
| `application_status_history` | Append-only audit trail of status transitions — powers the timeline UI and "days in stage" analytics |
| `reminders` | Follow-up dates tied to an application, with send state |
| `tags` / `application_tags` | (post-MVP) many-to-many labeling |

**users**
| column | type | notes |
|---|---|---|
| id | UUID/bigint, PK | |
| email | varchar, unique, not null | login identifier |
| password_hash | varchar, not null | BCrypt hash, never returned in any DTO |
| full_name | varchar | |
| created_at, updated_at | timestamp | |

**job_applications**
| column | type | notes |
|---|---|---|
| id | PK | |
| user_id | FK → users.id, not null, indexed | ownership boundary — every query filters on this |
| company_name | varchar, not null | |
| job_title | varchar, not null | |
| job_url | varchar, nullable | |
| status | enum, not null, indexed | current status, denormalized for fast filtering |
| applied_date | date | |
| location | varchar, nullable | |
| source | varchar, nullable | e.g. "LinkedIn", "Referral" |
| notes | text, nullable | |
| created_at, updated_at | timestamp | |

**application_status_history**
| column | type | notes |
|---|---|---|
| id | PK | |
| application_id | FK → job_applications.id, indexed | |
| status | enum, not null | |
| changed_at | timestamp, not null | |
| note | text, nullable | |

Why a separate history table instead of just overwriting `status`? "How long did I sit in Phone Screen before Onsite" is a real analytics question, and it's the kind of design choice worth being able to defend. The current `status` column on `job_applications` is a denormalized read-optimization — the history table is the source of truth.

**reminders**
| column | type | notes |
|---|---|---|
| id | PK | |
| application_id | FK → job_applications.id, indexed | |
| remind_at | timestamp, not null, indexed | scheduler queries on this |
| message | varchar, nullable | |
| status | enum: `PENDING`, `SENT`, `FAILED`, not null | drives idempotency (§9) |
| sent_at | timestamp, nullable | |

---

## 5. Main Entities

- **User** — identity + credentials, one-to-many with JobApplication
- **JobApplication** — the aggregate root of this domain; owns its status history and reminders conceptually (though they're separate tables, not embedded)
- **ApplicationStatusHistory** — immutable record, append-only
- **Reminder** — has its own small state machine (`PENDING → SENT` or `PENDING → FAILED`)
- **ApplicationStatus** (enum) — `APPLIED, ONLINE_ASSESSMENT, PHONE_SCREEN, ONSITE, OFFER, REJECTED, WITHDRAWN`
- **ReminderStatus** (enum) — `PENDING, SENT, FAILED`

Each entity maps 1:1 to a table above. No inheritance hierarchies — this domain doesn't warrant them.

---

## 6. REST API Endpoints

**Auth**
- `POST /api/auth/register` — create account
- `POST /api/auth/login` — returns JWT (+ refresh token)

**Users**
- `GET /api/users/me` — current user profile

**Applications**
- `GET /api/applications` — list, paginated, filterable by `status`, `company`, date range
- `POST /api/applications` — create
- `GET /api/applications/{id}` — detail
- `PUT /api/applications/{id}` — update
- `DELETE /api/applications/{id}` — delete
- `PATCH /api/applications/{id}/status` — change status (writes to `application_status_history` — kept separate from generic `PUT` because it's a distinct business action, not just a field edit)
- `GET /api/applications/{id}/history` — timeline

**Reminders**
- `POST /api/applications/{id}/reminders` — create reminder for an application
- `GET /api/applications/{id}/reminders` — list reminders for an application
- `DELETE /api/reminders/{id}` — cancel

**Dashboard**
- `GET /api/dashboard/summary` — counts by status, response rate, weekly trend

All endpoints except `/api/auth/**` require a valid JWT, and every query is implicitly scoped to `user_id = currentUser.id` at the service layer — never trust an `id` in the URL alone.

---

## 7. Authentication Flow

1. **Register**: password hashed with BCrypt before storage — plaintext never touches the DB or logs.
2. **Login**: credentials verified via Spring Security's `AuthenticationManager`; on success, issue:
   - a short-lived **access token** (JWT, e.g. 15 min expiry) — sent in `Authorization: Bearer` header on every request
   - a longer-lived **refresh token** (e.g. 7 days) — used only to mint new access tokens, stored more defensively (httpOnly cookie, ideally)
3. **Every request**: a custom `JwtAuthenticationFilter` runs before Spring Security's normal chain — extracts the token, validates signature + expiry, loads the user, populates the `SecurityContext`. No server-side session state — this is what makes the backend horizontally scalable.
4. **Authorization**: beyond "is this token valid," every service method also checks resource ownership (`application.getUser().getId().equals(currentUserId)`) before returning or mutating data — a separate concern from authentication, easy to forget.
5. **Signing key**: locally, a secret in an environment variable / `application.yml` (never committed). This is the seam where AWS Cognito could later replace our own JWT issuance — the resource-server side (validating tokens) barely changes, only *who issues* the token changes.

Rolling our own auth now (not Cognito) is deliberate — it's the part of the system most worth understanding deeply, and it's a clean, well-bounded swap-point for later.

---

## 8. Reminder Email Flow

1. User creates a `Reminder` on an application with a `remind_at` timestamp — status `PENDING`.
2. A scheduled job (see §9) polls for reminders where `remind_at <= now()` and `status = PENDING`.
3. For each due reminder, the job calls an `EmailSender` **interface** — not a concrete SMTP or SES class — with the recipient, subject, and templated body (e.g., "Follow up on your {jobTitle} application at {company}").
4. On successful send, the reminder is marked `SENT` (with `sent_at`); on failure, `FAILED` (so it's visible and not silently retried forever).
5. **Local implementation**: `EmailSender` backed by Spring's `JavaMailSender` pointed at Mailhog (a Dockerized fake SMTP server with a web UI) — so you actually *see* the email in a browser without real credentials or sending real mail during development.
6. **Production implementation**: a second `EmailSender` implementation backed by AWS SES, selected via Spring profile (`local` vs `prod`) — the scheduler and service code that *calls* `EmailSender` doesn't change at all. Dependency inversion doing real work: reminder logic depends on an abstraction it owns, and infrastructure plugs into it.

---

## 9. Scheduled Job Architecture

**Approach for MVP: polling, not push.** A Spring `@Scheduled` method runs every N minutes (e.g., every 5), queries `reminders` for due + `PENDING` rows, and processes them. For a single-instance, locally-run app, this is simpler and more debuggable than any message-queue setup — and it's *correct*, not just "good enough."

**Idempotency — the real design problem.** If the job takes longer than the polling interval, or the app restarts mid-batch, could a reminder be sent twice? Fix: use the `status` field as a claim mechanism — the query that fetches due reminders should atomically transition them from `PENDING` to an in-progress state (or use `SELECT ... FOR UPDATE SKIP LOCKED` semantics) before sending, so two overlapping runs can't both grab the same row.

**Why not SQS locally, even though it's in the target AWS stack?** SQS solves *distributed* coordination — multiple consumer instances safely sharing one queue. A single local Spring Boot instance polling its own database has no such coordination problem; introducing SQS (or LocalStack to fake it) here would be complexity with no payoff.

**Migration path to AWS**: when we actually run multiple backend instances, `@Scheduled` polling breaks down — multiple instances would all try to claim the same due reminders. Two options: (a) add [ShedLock](https://github.com/lukas-krecan/ShedLock) to ensure only one instance's scheduler fires at a time (minimal change), or (b) evolve to genuinely event-driven: on reminder creation, publish a delayed message to SQS instead of writing a row polled later. Not deciding this now — the point is the current design doesn't paint us into a corner.

---

## 10. Recommended Folder Structure

**Backend** (Maven, Java 21) — packaged by feature, layered within each feature:

```
com.jobpulse
├── auth/          → controller, service, JwtService, JwtAuthFilter, dto
├── user/          → entity, repository, service
├── application/   → entity, repository, service, controller, dto
├── reminder/      → entity, repository, service, controller, scheduler
├── email/         → EmailSender interface, SmtpEmailSender, SesEmailSender
├── common/        → global exception handler, base auditing entity, pagination helpers
└── config/        → SecurityConfig, OpenApiConfig, SchedulingConfig
```

Feature-first packaging (not `controllers/`, `services/`, `repositories/` as top-level) means everything relevant to "reminders" lives together — easier to navigate, and enforces that features don't casually reach into each other's internals.

**Frontend** (React + Vite):

```
src/
├── api/         → axios instance (JWT interceptor), typed API call functions
├── features/    → auth/, applications/, reminders/, dashboard/ (each: components, hooks, api calls)
├── components/  → shared, feature-agnostic UI (Button, Modal, Table)
├── context/     → AuthContext (current user, token state)
├── routes/      → route definitions, ProtectedRoute wrapper
└── utils/
```

---

## 11. Development Roadmap (Milestones)

| # | Milestone | Deliverable |
|---|---|---|
| M0 | Scaffolding | Spring Boot skeleton + React skeleton + Docker Compose (Postgres) — nothing functional yet, just a running shell |
| M1 | Auth | Register/login, JWT issuance & validation, password hashing |
| M2 | Applications CRUD | Backend endpoints + entity/schema, minimal frontend list/form |
| M3 | Status history | `PATCH /status` endpoint, history table, timeline UI |
| M4 | Reminders (data layer) | Reminder CRUD, no email yet |
| M5 | Scheduler + local email | `@Scheduled` job, idempotent claim logic, Mailhog integration |
| M6 | Dashboard | Aggregate query endpoint + simple charts on frontend |
| M7 | Search/filter/pagination | Query params on list endpoint, frontend controls |
| M8 | Frontend polish | Protected routes, auth UX, error states |
| M9 | Full Dockerization | docker-compose for backend+frontend+db+mailhog, one-command local run |
| M10 | CI | GitHub Actions: build + test on push |
| M11 | AWS migration prep | Profile-based config, SES swap verified, notes on Cognito/RDS/S3/CloudFront migration |

Each milestone is small enough to review in one sitting, ends in something runnable, and gets a suggested commit message on approval.

---

## 12. Local Development Alternatives for AWS Services

Every AWS service in the target stack has a free, local, Dockerized (or simpler) stand-in during development. The rule guiding every choice below: **prefer the option that speaks the same protocol/API as the real AWS service**, so the migration later is a config change, not a rewrite.

### Cognito (auth / user pools)

| | |
|---|---|
| **Local replacement** | Our own Spring Security + JWT implementation (already designed in §7) — no separate tool needed |
| **Why it's a good replacement** | Cognito's job is issuing and validating JWTs and managing user credentials. We're already building exactly that ourselves. There's nothing to "emulate" — self-hosted auth *is* the free local version, and it's also the part of the system most worth understanding deeply rather than outsourcing to a managed service from day one |
| **Migration difficulty** | **Medium.** Register/login endpoints get replaced or fronted by Cognito's hosted UI/SDK, and JWT validation switches from verifying our own signing key to verifying Cognito's public JWKS. Because token *validation* is already isolated in a `JwtAuthenticationFilter`, this change stays contained to the `auth/` module — the rest of the app (services checking `SecurityContext`) doesn't change |

### SES (email delivery)

| | |
|---|---|
| **Local replacement** | Mailhog — a Dockerized fake SMTP server with a web UI that catches every email sent, so you can actually read them in a browser |
| **Why it's a good replacement** | Free, one Docker container, zero external account or verified sender domain needed. It speaks real SMTP, so `JavaMailSender` code behaves exactly as it would against a real mail server |
| **Migration difficulty** | **Low.** The design already isolates sending behind an `EmailSender` interface (§8). Adding an `SesEmailSender` implementation (using the AWS SES SDK) and flipping the active Spring profile is the entire migration — zero changes to reminder/business logic |

### SQS (message queue)

| | |
|---|---|
| **Local replacement** | None — the `reminders` table itself, polled by `@Scheduled` (already designed in §9), replaces the queue entirely for local/single-instance use |
| **Why it's a good replacement** | SQS solves cross-instance distributed coordination, which doesn't exist yet with one backend instance. A DB-polling design is simpler, free, and fully correct at this scale — introducing a queue (even a fake one) here would be complexity with no payoff |
| **Migration difficulty** | **Medium.** This is the one honest exception to "just swap a config." Moving from DB polling to real SQS means introducing a producer (on reminder creation) and a consumer (listener), plus handling message visibility timeouts and retries — a small architectural addition, not just a new adapter class. It stays manageable *if* we keep the "decide a reminder is due" logic behind one clean interface now, so only the dispatch mechanism changes later. *(Optional alternative if you want to practice the real SQS API before deploying: [LocalStack](https://www.localstack.io/) emulates SQS locally for free — same SDK calls, `localhost` endpoint instead of AWS. Not necessary for MVP, but worth knowing about.)* |

### RDS (managed PostgreSQL)

| | |
|---|---|
| **Local replacement** | PostgreSQL running in Docker (official `postgres` image via docker-compose) |
| **Why it's a good replacement** | It's not an emulation — it's the exact same database engine RDS runs under the hood. Identical SQL dialect, identical JDBC driver, identical migration scripts |
| **Migration difficulty** | **Very low.** Change the JDBC connection string, username/password (ideally via environment variables/secrets already, not hardcoded), and run the same Flyway/Liquibase migrations against the new endpoint. No code changes |

### S3 (object storage — resumes/documents, post-MVP)

| | |
|---|---|
| **Local replacement** | MinIO — a Dockerized, S3-API-compatible object storage server |
| **Why it's a good replacement** | MinIO implements the actual S3 API (`PutObject`, `GetObject`, presigned URLs), so code written against the AWS S3 SDK works against it unchanged — the highest-fidelity replacement in this list |
| **Migration difficulty** | **Very low.** Change the S3 client's endpoint URL and credentials from `http://localhost:9000` to AWS's endpoint. The AWS SDK is explicitly designed to target any S3-compatible endpoint, so this is a config change. (Since file upload is a post-MVP feature, we can defer standing up MinIO until we actually build that milestone) |

### CloudFront (CDN for frontend static assets)

| | |
|---|---|
| **Local replacement** | None needed — the Vite dev server (or a plain static file server / nginx container for a prod-like local build) |
| **Why it's a good replacement** | A CDN's entire purpose is edge caching for geographically distributed users, which is meaningless with one developer on one machine. Skipping it locally costs nothing |
| **Migration difficulty** | **Low.** CloudFront is added purely as an infrastructure layer in front of the deployed frontend (typically fronting an S3 static site or a load balancer) — no application code changes, just deployment configuration when we actually deploy |

### Summary

| AWS Service | Local Replacement | Migration Difficulty |
|---|---|---|
| Cognito | Self-hosted Spring Security + JWT | Medium |
| SES | Mailhog (Docker SMTP catcher) | Low |
| SQS | DB-polled `reminders` table | Medium |
| RDS | PostgreSQL in Docker | Very Low |
| S3 | MinIO (Docker) | Very Low |
| CloudFront | None (Vite dev server / nginx) | Low |

The consistent theme: every local replacement was chosen to speak the *same protocol or API* as its AWS counterpart wherever possible (Postgres↔Postgres, SMTP↔SMTP, S3 API↔S3 API), so migration is a configuration change rather than a rewrite. The one deliberate exception is SQS, where we're intentionally not paying for architectural complexity (distributed queuing) that a single local instance doesn't need yet.
