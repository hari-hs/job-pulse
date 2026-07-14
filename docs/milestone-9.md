# Milestone 9 — Full Dockerization Walkthrough

_A junior-engineer-level walkthrough of turning three separate manual startup steps into one command — and a real timing bug this milestone's own verification caught. Companion to `docs/milestone-0.md` through `docs/milestone-7.md` — read those first if you haven't. (M8, Frontend polish, was deliberately skipped — see "What's next" below.)_

---

## What M9 delivers

`DESIGN.md`'s roadmap (§11): "Full Dockerization — docker-compose for backend+frontend+db+mailhog, one-command local run." Every milestone before this one containerized only the *infrastructure* (Postgres since M0, Mailhog since M5) — the app itself always ran natively (`./mvnw spring-boot:run`, `npm run dev`), in whatever terminals you happened to have open. This milestone containerizes the app too:

```bash
docker compose up --build
```

...and that's the entire setup. No Java, no Node, no Maven, no npm need to be installed on the host at all — just Docker.

---

## The backend image — a two-stage build

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

Two `FROM` lines, two stages, only the second one ships. The first stage has the full JDK and Maven — genuinely needed to compile the project and produce the executable jar, but a real liability to ship in a runtime image (larger image, and a JDK/build-toolchain sitting in a production container is extra attack surface nobody needs there). The second stage starts completely fresh from a `*-jre-alpine` base — no compiler, no Maven, nothing but the JVM needed to *run* an already-built jar — and copies over exactly one file: the jar itself. `COPY --from=build` is what makes this possible: it reaches back into the *first* stage's filesystem to grab one artifact, then everything else about that stage (the JDK, Maven's `~/.m2` cache, the source tree) is discarded and never touches the final image.

`COPY pom.xml .` and the `mvn dependency:go-offline` step happen *before* `COPY src src` deliberately — Docker caches each layer, keyed on that layer's inputs. As long as `pom.xml` hasn't changed, Docker reuses the cached "downloaded every dependency" layer even when source code changes on every single build, so only the actual compile step re-runs on a typical edit-rebuild cycle, not a full dependency re-download.

One thing this Dockerfile does **not** use: the `./mvnw` wrapper M0 introduced specifically so every developer builds with an identical, pinned Maven version without installing Maven themselves. Inside this container, `maven:3.9-eclipse-temurin-21` already *is* a pinned Maven version — the wrapper's whole purpose is already satisfied by picking a specific base image tag, so `mvn` (the image's own Maven) is used directly instead.

---

## The frontend image — a static build served by nginx, replaying M2's proxy trick

```dockerfile
FROM node:22-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build

FROM nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
```

Same two-stage shape as the backend: Node (needed to run Vite's build) never ships; only the static output (`dist/`, plain HTML/CSS/JS) does, served by `nginx` — a purpose-built static file server, not a general-purpose runtime.

This raises the exact CORS question `docs/milestone-0.md` first flagged and M2 answered with Vite's dev-server proxy (`vite.config.js`'s `server.proxy: { '/api': 'http://localhost:8080' }`). That proxy only exists inside `vite dev` — a production static build has no Vite process running at all to do any proxying. `nginx.conf` replays the identical trick at the web-server layer instead:

```nginx
location /api/ {
    proxy_pass http://backend:8080/api/;
}

location / {
    root /usr/share/nginx/html;
    try_files $uri $uri/ /index.html;
}
```

`http://backend:8080` is not a typo or a placeholder — `backend` genuinely resolves, via Docker Compose's built-in service-name DNS (every service in a compose file is reachable by its service name from every other service on the same network, automatically, no configuration required). Because the frontend's own code has always called a relative `/api/...` path (`baseURL: '/api'` in `api/client.js`, since M2) and never a hardcoded `localhost:8080`, **zero frontend code changed for this milestone** — the exact same built JavaScript bundle runs identically under `npm run dev` and under this container; only *what's doing the proxying* differs (Vite's dev server vs. nginx).

---

## Wiring it together — `docker-compose.yml`

Two new services join the existing `postgres` and `mailhog`:

```yaml
backend:
  build: ./backend
  environment:
    DB_HOST: postgres
    DB_PORT: 5432
    MAIL_HOST: mailhog
    MAIL_PORT: 1025
  depends_on:
    postgres:
      condition: service_healthy
    mailhog:
      condition: service_started

frontend:
  build: ./frontend
  ports:
    - "5173:80"
  depends_on:
    backend:
      condition: service_healthy
```

**`DB_PORT: 5432`, not `5433`, is the one detail most likely to trip someone up here.** Every milestone since M0 has used `5433` — that's the *host* port, the one exposed to tools running outside Docker (your own terminal's `psql`, or a locally-run backend during dev). Inside the Compose network, containers talk to each other over the *container's own* port, which for Postgres is always its standard `5432` — the `5433:5432` mapping in `postgres`'s own `ports:` block never applies to container-to-container traffic at all, only to the host. Getting this backwards (setting `DB_PORT: 5433` here) would have the backend container trying to reach a port Postgres isn't actually listening on from inside the network, and failing to connect entirely.

Zero backend code changed to make this work, either — `application.yml`'s `${DB_HOST:localhost}` / `${MAIL_HOST:localhost}` placeholders have been there since M1 and M5 respectively, precisely for this: an env var flips the target with no code touched, the same 12-factor pattern this project has followed from the start. `JWT_SECRET` is set explicitly here too, mostly to demonstrate the same override mechanism working end-to-end rather than out of any real necessity — this is still local dev tooling, not a deployment.

`depends_on` with a `condition` (not just plain `depends_on: postgres`, which only waits for the container to *start*, not to be *ready*) is what makes the startup order actually correct: `postgres`'s existing `healthcheck` (from M0) gates `backend` from starting until Postgres can genuinely accept connections, and a new `healthcheck` on `backend` itself (hitting `/actuator/health`, the same endpoint every milestone's manual verification has curled since M0) gates `frontend` from starting until the backend is actually ready to serve requests — not just that its process exists.

---

## The bug this milestone's own verification caught

The first full `docker compose up --build` succeeded completely — both images built, all four containers came up in the right order, and a real end-to-end test through the containerized stack worked (registered a user via `POST http://localhost:5173/api/auth/register` — proving nginx's proxy genuinely reaches the backend container — created an application, viewed the dashboard, and confirmed a reminder email actually made it to the Mailhog container via `MAIL_HOST=mailhog` container-to-container SMTP).

Then, as part of re-verifying from a clean `docker compose down` + `up --build` (checking what a fresh clone would actually experience, not just what an already-warmed-up environment does), the **backend container failed its healthcheck** and the frontend refused to start as a result:

```
Container jobpulse-backend Error dependency backend failed to start
dependency failed to start: container jobpulse-backend is unhealthy
```

The backend's own logs showed nothing wrong — no exception, no stack trace, just a normal startup sequence running unusually slowly (Tomcat initialization alone took 38 seconds in that run). This matches a pattern already noted earlier in this project's own memory: Spring Boot's startup time on this particular machine has ranged anywhere from ~15 seconds to ~90 seconds depending on machine load at the time, independent of anything Docker-specific. The healthcheck's original timing budget — `start_period: 30s` plus 6 retries at 10-second intervals — gave the container roughly 90 seconds total grace before Compose gave up and marked it unhealthy. On a slow-boot run, that budget ran out before `Started JobPulseApplication` ever printed.

**The fix**: widen `start_period` to 120 seconds.

```yaml
healthcheck:
  test: ["CMD", "wget", "-qO-", "http://localhost:8080/actuator/health"]
  interval: 10s
  timeout: 5s
  retries: 6
  start_period: 120s
```

`start_period` matters specifically because of what it means, not just its number: any failed check *during* `start_period` doesn't count against the `retries` budget at all — it's a pure grace window before the retry-counting clock even starts. Widening it to 120 seconds — comfortably past the slowest boot this project has actually observed — was reconfirmed by immediately re-testing: a repeat `docker compose down && up --build` came up healthy cleanly, and a subsequent `./mvnw test` run (unrelated to Docker, just confirming the underlying slowness theory) also took 90 seconds on this same machine at this same moment, correlating the slow boot with genuine machine load rather than anything wrong with the container itself.

**The general lesson**: a healthcheck's timing budget needs to be set against the *worst* observed startup time for the actual application, not a hopeful average — and for a JVM app in particular, that number is worth deliberately re-checking against real logs (as this project's own memory already tracks: this machine's Spring Boot boot times have ranged 15–90+ seconds across this project's sessions) rather than copying a default that looked reasonable in isolation.

---

## Verification

| Check | Expected | Got |
|---|---|---|
| `docker compose up --build` (clean, no cache) | both images build, all 4 containers start in dependency order | ✅ |
| `curl http://localhost:5173/` | `200`, serves the built `index.html` | ✅ |
| `curl -X POST http://localhost:5173/api/auth/register` | `201` — proves nginx's `/api/` proxy genuinely reaches the backend container | ✅ |
| Full browser flow through `localhost:5173` (register → create application → view dashboard) | works identically to the non-Docker dev setup | ✅ (Playwright, real screenshots reviewed) |
| Reminder created via the dockerized backend, `MAIL_HOST=mailhog` | actual email lands in the Mailhog container, confirmed via its HTTP API | ✅ |
| `docker compose down && up --build` from clean (repeated, to catch the healthcheck bug above) | all containers healthy without manual intervention | ✅ (after the `start_period` fix) |
| `./mvnw test` (run natively against the dockerized Postgres on its host-mapped `5433` port) | passes | ✅ |

---

## What's next

M8 (Frontend polish — protected routes, richer auth UX, `react-router-dom`) was deliberately skipped rather than done in order: discussed directly, and decided the app already behaves correctly without it (the `token ? <AppShell> : <AuthForm>` conditional in `App.jsx` already prevents any unauthenticated view of the app's contents; there just aren't real URLs to protect yet), and that effort was better spent on M9's actual one-command setup — a bigger, more visible improvement to "does this look and feel like a real, complete project" than route guarding would have been. M10 (CI — GitHub Actions build + test on push) is next on the roadmap, and this milestone's own Dockerfiles are a natural foundation for it: a CI pipeline can now build the exact same images a real deployment would use, not a separately-defined "CI-only" build process.
