# Milestone 0 — Project Walkthrough

_A junior-engineer-level walkthrough of everything Milestone 0 created: every folder, every important file, why it exists, and how the pieces fit together. No code was changed while writing this — it's a guided tour of what already exists._

---

## Top-level layout

```
JobPulse - Job application tracker/
├── .git/                  → version control metadata
├── .gitignore              → root-level ignore rules (OS files, .env, .claude/)
├── DESIGN.md                → our design doc
├── docker-compose.yml       → defines the Postgres container
├── backend/                → Spring Boot API (Java)
└── frontend/                → React app (Vite)
```

Two independent projects, each with its own build tool and dependency manager, living side by side under one repo. Nothing here makes them a single build — they're deployed and run separately, which matches the architecture in `DESIGN.md` §3.

---

## `backend/` — folder by folder

```
backend/
├── mvnw, mvnw.cmd            → Maven Wrapper scripts (Unix / Windows)
├── .mvn/wrapper/maven-wrapper.properties
├── pom.xml                    → the build file — this is the important one
├── HELP.md                    → auto-generated, no functional role, safe to ignore
├── .gitattributes, .gitignore
├── src/main/java/com/jobpulse/JobPulseApplication.java   → entry point
├── src/main/resources/application.yml                      → config
└── src/test/java/com/jobpulse/JobPulseApplicationTests.java → smoke test
```

**`mvnw` / `mvnw.cmd` / `.mvn/wrapper/...`** — the **Maven Wrapper**. This is *why* we run `./mvnw spring-boot:run` instead of `mvn spring-boot:run`. It pins an exact Maven version to this project and downloads it on first use if you don't have it — so your machine, a teammate's machine, and CI all build with the identical Maven version without anyone installing Maven globally. You should never need to touch these files.

**`pom.xml`** — Maven's "Project Object Model," the actual build definition. Reading it top to bottom:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.1.0</version>
</parent>
```
This isn't a dependency — it's a **parent POM** that gives us sane defaults for free: a curated Bill of Materials (BOM) that pins compatible versions of every Spring dependency (so we never have to write a version number ourselves and risk two Spring libraries that don't get along), plus sensible compiler/plugin defaults.

```xml
<properties>
  <java.version>21</java.version>
</properties>
```
Tells the parent POM's compiler plugin to target Java 21 bytecode — regardless of the fact that your JDK is actually 22 (a newer JDK can always compile *down* to an older bytecode target).

```xml
<dependencies>
  spring-boot-starter-actuator
  spring-boot-starter-data-jpa
  spring-boot-starter-validation
  spring-boot-starter-webmvc
  postgresql (runtime scope)
  ... four "-test" scoped starters
</dependencies>
```
Worth flagging explicitly: **`spring-boot-starter-webmvc`, not `spring-boot-starter-web`.** Spring Boot 4 (released after most tutorials online were written, which target Boot 3) renamed/split this starter to disambiguate from WebFlux. If you go searching Stack Overflow or older guides and see `spring-boot-starter-web`, that's the Boot 3 name for the same thing. Similarly, Boot 3 tutorials use one `spring-boot-starter-test` for everything; Boot 4 splits it into per-starter test companions (`-data-jpa-test`, `-webmvc-test`, etc.) so you only pull in the testing utilities for what you actually use. Good to know so you're not confused later when a tutorial doesn't match what's in our `pom.xml`.

```xml
<build><plugins><plugin>spring-boot-maven-plugin</plugin></plugins></build>
```
This plugin is what makes `./mvnw spring-boot:run` work at all (runs the app directly without packaging first), and later makes `./mvnw package` produce an executable "fat jar" — a single `.jar` with all dependencies bundled inside, runnable with just `java -jar`. That's the artifact we'll eventually put in a Docker image.

**`JobPulseApplication.java`** — the entry point:
```java
@SpringBootApplication
public class JobPulseApplication {
    public static void main(String[] args) {
        SpringApplication.run(JobPulseApplication.class, args);
    }
}
```
`@SpringBootApplication` is itself a combination of three annotations: `@Configuration` (this class can define beans), `@EnableAutoConfiguration` (let Spring Boot guess sensible beans based on what's on the classpath — e.g., "I see the Postgres driver and Data JPA, let me configure a DataSource"), and `@ComponentScan` (scan this package and everything *underneath* it for `@Component`/`@Service`/`@Controller`/etc. classes to register). That last point matters architecturally: **every feature package we build later (`auth`, `application`, `reminder`) must live under `com.jobpulse.*`** to be auto-discovered — this file's package location is why our folder structure in `DESIGN.md` §10 is rooted at `com.jobpulse`.

**`JobPulseApplicationTests.java`** — currently a single "smoke test":
```java
@SpringBootTest
class JobPulseApplicationTests {
    @Test void contextLoads() {}
}
```
This looks like it does nothing, but it's actually useful: `@SpringBootTest` boots the *entire* application context (every bean, every auto-configuration, a real attempt to connect the DataSource) inside the test JVM. If any bean is misconfigured — a missing property, a circular dependency, a bad datasource URL — this test fails immediately, even though its body is empty. It's a cheap regression trip-wire we'll keep forever.

**`application.yml`** — covered in detail in the "configuration" section below.

**`target/`** — not tracked in git (gitignored) because it exists only after you build/run: compiled `.class` files and the packaged jar. Fully regenerable, never edit anything in there by hand.

---

## `frontend/` — folder by folder

```
frontend/
├── package.json, package-lock.json     → npm manifest + exact lockfile
├── vite.config.js                       → build tool config
├── index.html                            → real entry page (not in public/)
├── src/
│   ├── main.jsx                         → JS entry point
│   ├── App.jsx, App.css                  → demo component (to be replaced)
│   ├── index.css
│   └── assets/ (react.svg, vite.svg, hero.png)
├── public/
│   ├── favicon.svg
│   └── icons.svg
├── .oxlintrc.json                        → see note below
├── .gitignore
└── README.md
```

**`package.json`** — npm's manifest:
```json
"scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" }
```
- `npm run dev` → starts Vite's dev server (port 5173)
- `npm run build` → produces an optimized static bundle in `dist/` for deployment
- `npm run preview` → serves that built `dist/` locally, to sanity-check a production build before deploying

```json
"dependencies": { "react": "...", "react-dom": "..." }
"devDependencies": { "vite": "...", "@vitejs/plugin-react": "...", "@types/react*": "..." }
```
The distinction matters: `dependencies` ship inside the JavaScript bundle that runs in the user's browser. `devDependencies` are build-time-only tools (the bundler, the JSX compiler, type stubs for editor autocomplete) — they never reach the browser; Vite strips them out entirely when it builds.

**`vite.config.js`**:
```js
export default defineConfig({ plugins: [react()] })
```
Minimal right now — just registers the React plugin, which gives Vite the ability to transform JSX syntax and enables Fast Refresh (hot-reloading a component without losing its state). This file is where we'll later add a **dev-server proxy** to the backend — see the communication section below.

**`index.html`** — lives at the project **root**, not inside `public/`, unlike older tools like Create React App. Vite treats this file as both the real HTML entry point *and* a template it processes. The key line:
```html
<script type="module" src="/src/main.jsx"></script>
```
`type="module"` means the browser loads this as a native ES module — in dev mode, Vite doesn't bundle your code at all; it serves each file as its own native browser-fetched module and transforms them on the fly. That's why the dev server starts in about a second — there's no bundling step to wait for, unlike Webpack-based tooling.

**`src/main.jsx`**:
```jsx
createRoot(document.getElementById('root')).render(
  <StrictMode><App /></StrictMode>
)
```
`createRoot` is React 18+'s "concurrent root" API — this is what actually mounts your component tree into the `<div id="root">` from `index.html`. `<StrictMode>` is a dev-only wrapper that intentionally double-invokes certain functions to help you catch side-effect bugs early; it's automatically stripped in production builds and has zero runtime cost there.

**`src/App.jsx`, `App.css`, `index.css`, `src/assets/*`** — this is all Vite's default demo page (the counter button, React/Vite logo links). Purely scaffolding proof-of-life; every line of it gets replaced once we build the real application UI starting M2.

**`public/` vs `src/assets/`** — a distinction worth understanding now since it'll matter later: files in `public/` (like `favicon.svg`) are copied to the output **as-is**, served at the exact same path (`/favicon.svg`), never processed or imported in JS. Files in `src/assets/` (like `hero.png`, imported via `import heroImg from './assets/hero.png'` in `App.jsx`) are treated as *modules* — Vite processes them, content-hashes the filename for cache-busting, and only includes them in the final build if something actually imports them.

**Noticed but not touched:** `.oxlintrc.json` configures a linter called `oxlint`. When the Vite 8 → Vite 6 native-binding issue was fixed during M0, the `oxlint` package itself was removed from `package.json`'s devDependencies (it came bundled with the newer scaffold template) — but this config file was left behind, now referencing a linter that isn't installed. Harmless (nothing runs it), but orphaned. Worth deleting or replacing with a real linter (ESLint is the more standard choice) in a later milestone.

---

## How backend and frontend communicate

Right now: **they don't.** This is deliberate for M0. Two separate processes on two separate ports:
- Backend: `http://localhost:8080` (only endpoint that exists: `/actuator/health`)
- Frontend: `http://localhost:5173` (nothing in `App.jsx` makes an HTTP call to anything)

Starting around M1/M2, when the frontend needs to call, say, `POST /api/auth/login`, we'll hit a genuinely important browser constraint: **CORS**. The browser treats `localhost:5173` and `localhost:8080` as different origins (different ports = different origin, even on the same host), so a raw `fetch('http://localhost:8080/api/...')` from the frontend will be blocked unless the backend explicitly allows it. Two options when we get there:
1. Configure Spring Security's CORS policy to allow the Vite origin, and call the full URL from the frontend.
2. Configure a **dev-server proxy** in `vite.config.js` (`server.proxy: { '/api': 'http://localhost:8080' }`) so the frontend calls a same-origin relative path like `/api/auth/login`, and Vite silently forwards it to the backend — sidestepping CORS entirely in dev.

Not decided yet — that's an M1 design conversation, not something built in M0.

---

## How Docker Compose works

```yaml
services:
  postgres:
    image: postgres:16
    container_name: jobpulse-postgres
    environment:
      POSTGRES_DB: jobpulse
      POSTGRES_USER: jobpulse
      POSTGRES_PASSWORD: jobpulse
    ports:
      - "5433:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U jobpulse -d jobpulse"]
```

Line by line:
- `services:` — each key underneath is one container Compose knows how to manage. We have exactly one: `postgres`. (Later: `mailhog`, `minio` join this file as their own service blocks — nothing more than copy-paste-adjust.)
- `image: postgres:16` — pulls the official Postgres image, version-pinned to major version 16, so a fresh clone of this repo gets the exact same DB engine version, not "whatever `latest` happens to be today."
- `environment:` — these env vars are read by the postgres image's own startup script **only the first time the container initializes its data directory** — that's when it creates the `jobpulse` database, the `jobpulse` user, and sets that password. If you already have data in the volume, changing these values later won't retroactively change anything.
- `ports: "5433:5432"` — format is `HOST:CONTAINER`. Postgres inside the container always listens on its standard port 5432; Docker forwards your Mac's port 5433 to it. We picked 5433 specifically because your Mac already runs a *native* (non-Docker) Postgres 16 service occupying port 5432 — this was the exact conflict debugged live during M0.
- `volumes: - pgdata:/var/lib/postgresql/data` — this is a **named volume**, storage that Docker manages outside the container itself. Containers are meant to be disposable — `docker compose down` destroys the container — but the volume survives that. Without this line, every time you recreated the container you'd lose all your data; with it, `pgdata` persists across restarts/recreations. (`docker compose down -v` *does* delete the volume too — that's the one genuinely destructive variant to be careful with.)
- `healthcheck:` — Docker periodically runs `pg_isready` *inside* the container to distinguish "the process has started" from "the database is actually ready to accept queries" (these aren't the same moment). This is what produced the `(health: starting)` → eventually `(healthy)` status visible in `docker compose ps`. It'll matter more once a second service needs to wait for Postgres to be truly ready before it starts (`depends_on: condition: service_healthy`).

Worth being explicit: **this file currently only runs Postgres in Docker.** The backend and frontend still run natively on your machine (`./mvnw spring-boot:run`, `npm run dev`) — we haven't containerized *our own application code* yet. That's M9 ("Full Dockerization") in the roadmap, a deliberately separate, later step.

---

## How PostgreSQL is connected

The full chain, in order:

1. `docker compose up -d postgres` starts the container. Postgres listens on port 5432 *inside* its own network namespace; Docker forwards your Mac's `localhost:5433` to it.
2. The backend's `application.yml` has:
   ```yaml
   url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5433}/${DB_NAME:jobpulse}
   username: ${DB_USER:jobpulse}
   password: ${DB_PASSWORD:jobpulse}
   ```
   None of `DB_HOST`, `DB_PORT`, etc. are set as real environment variables right now, so every one of these falls back to its default (after the colon) — which is exactly why it works with zero extra setup: `localhost:5433/jobpulse`, credentials `jobpulse`/`jobpulse`, matching precisely what's in `docker-compose.yml`'s `environment:` block.
3. At startup, Spring Boot's auto-configuration notices the Postgres driver + Data JPA on the classpath plus this `spring.datasource.*` config, and wires up a `DataSource` bean backed by **HikariCP** — the connection-pool library bundled by default. A connection pool keeps a handful of already-open TCP connections to Postgres ready to reuse, instead of paying the cost of opening a brand-new TCP + auth handshake for every single query. This showed up in the boot log as: `HikariPool-1 - Added connection...`.
4. Hibernate (our JPA provider) uses that pooled `DataSource` to execute SQL.
5. `ddl-auto: none` — tells Hibernate: *never* auto-create or auto-alter tables based on entity classes. Some setups default this to `update`, which is genuinely dangerous once real data exists (Hibernate's schema-guessing can silently do the wrong thing). We have zero entities right now, so it's a no-op today, but it's a deliberate stance: schema changes will go through an explicit migration tool (Flyway, decided at M1) that we can read, review, and version — not something Hibernate improvises.
6. Actuator auto-detects that `DataSource` bean and adds a `db` component to `/actuator/health`, which runs a trivial `Connection.isValid()` check through the pool. The `"db":{"status":"UP"}` seen during M0 verification is this exact mechanism — genuine proof the JDBC connection works, not a hardcoded response.

---

## How configuration is loaded

- `application.yml` under `src/main/resources` is picked up **automatically** — Spring Boot looks for a file named exactly `application.properties` or `application.yml` on the classpath at startup, no wiring required on our part.
- YAML over `.properties` was chosen because it nests naturally (`spring.datasource.url` reads as an actual tree, `spring: datasource: url:`) rather than a flat dotted string — more readable as the config grows.
- The `${VAR:default}` syntax is **Spring property placeholder resolution**. At startup, Spring checks (roughly, in priority order): command-line arguments → OS environment variables → this file's own values → Spring Boot's own defaults. Today, with no env vars exported, every placeholder falls through to its literal default. But if you later run:
  ```
  export DB_HOST=my-rds-instance.amazonaws.com
  ```
  before starting the jar, that value wins automatically — **zero code or config-file changes needed.** This is precisely the seam `DESIGN.md` §12 depends on for the eventual RDS migration.
- `management.endpoints.web.exposure.include: health,info` — Actuator, by default, exposes almost nothing over HTTP for security reasons (some of its endpoints can leak internals like environment variables or bean names). This line is a deliberate, minimal opt-in — just `health` and `info` — not "expose everything."
- No Spring **Profiles** yet (`application-local.yml`, `application-prod.yml`, activated via `SPRING_PROFILES_ACTIVE`). That's the mechanism we'll actually use to implement the local-vs-AWS swap (e.g., a `prod` profile pointing at SES instead of Mailhog) — no need yet since there's only one environment so far.
- Frontend side: Vite loads `vite.config.js` automatically when you run the `vite` CLI — no import needed. It also supports `.env` files (variables prefixed `VITE_` get exposed to browser code) — not needed yet since the frontend doesn't talk to the backend at all right now; that'll appear once the frontend needs to know the backend's base URL.

---

## How to run each piece independently

**Postgres only:**
```
docker compose up -d postgres      # start (detached / background)
docker compose ps                  # check status — look for "healthy"
docker compose logs postgres       # see DB logs
docker compose down                # stop + remove container — data survives (named volume)
docker compose down -v             # stop + remove container AND delete the volume — wipes data, be careful
```

**Backend only:**
```
cd backend
./mvnw spring-boot:run
```
This needs Postgres reachable at whatever `DB_HOST:DB_PORT` resolves to — if Postgres isn't running, Hikari fails fast on startup with a clear connection error. Runs on `localhost:8080`.

**Frontend only:**
```
cd frontend
npm install     # only needed once, or after package.json changes
npm run dev
```
Runs on `localhost:5173`, completely independent of the backend right now — just the default Vite/React demo page.

**Running everything:** order only matters in one direction — Postgres has to be up *before* the backend starts (backend needs it to boot at all), but the frontend has no dependency on either right now. Practical order: `docker compose up -d postgres` → `./mvnw spring-boot:run` → `npm run dev`, in three separate terminal tabs.
