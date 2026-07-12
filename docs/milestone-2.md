# Milestone 2 — Applications CRUD Walkthrough

_A junior-engineer-level walkthrough of everything Milestone 2 added: every new file, why it exists, and one real correctness bug caught and fixed during verification. Companion to `docs/milestone-0.md` and `docs/milestone-1.md` — read those first if you haven't._

---

## What M2 delivers

`DESIGN.md`'s roadmap (§11) scopes M2 as "Applications CRUD: Backend endpoints + entity/schema, minimal frontend list/form." **This milestone covers the backend half only** — the frontend list/form was deliberately deferred (asked and confirmed explicitly before starting) since the frontend is still the untouched Vite/React demo page from M0, and mixing a first real UI screen into the same pass as new backend endpoints would make both harder to verify cleanly. The frontend remains a clean follow-up.

What's live by the end of this milestone:

- `POST /api/applications` — create a job application, owned by whoever's JWT made the request
- `GET /api/applications` — list the current user's applications
- `GET /api/applications/{id}` — fetch one, but only if it belongs to the current user
- `PUT /api/applications/{id}` — full update
- `DELETE /api/applications/{id}` — delete
- A real `job_applications` table via a second Flyway migration
- Every single one of the above enforces **per-user ownership** — this is the milestone where `DESIGN.md` §6's "never trust an id in the URL alone" rule first actually matters, since M1 had no resource that a *different* user could try to reach.

---

## The migration — `V2__create_job_applications_table.sql`

```sql
CREATE TABLE job_applications (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    company_name VARCHAR(255) NOT NULL,
    job_title VARCHAR(255) NOT NULL,
    job_url VARCHAR(2048),
    status VARCHAR(30) NOT NULL,
    applied_date DATE,
    location VARCHAR(255),
    source VARCHAR(255),
    notes TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT chk_job_applications_status CHECK (status IN (
        'APPLIED', 'ONLINE_ASSESSMENT', 'PHONE_SCREEN', 'ONSITE', 'OFFER', 'REJECTED', 'WITHDRAWN'
    ))
);

CREATE INDEX idx_job_applications_user_id ON job_applications(user_id);
CREATE INDEX idx_job_applications_status ON job_applications(status);
CREATE INDEX idx_job_applications_applied_date ON job_applications(applied_date);
```

This is `V2`, sitting right alongside M1's `V1__create_users_table.sql` in `db/migration/` — Flyway just picks up the next number and applies it on the next startup, no config changes needed (the whole point of the versioned-migration approach M1 introduced).

Two decisions worth calling out:

- **`status` is `VARCHAR(30)` with a `CHECK` constraint, not a native Postgres `ENUM` type.** A native enum type is genuinely faster and self-documenting at the schema level, but altering one later (adding a new status value) requires a dedicated `ALTER TYPE ... ADD VALUE` migration with its own quirks (can't run inside certain transaction blocks, for one). A `VARCHAR` + `CHECK` gets 95% of the same safety — the database still rejects an invalid value — while staying a plain, boring `ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT` to change later. For a status list that's genuinely likely to grow (this is a job tracker; "ghosted," "withdrew after offer," etc. are all plausible future additions), that flexibility was worth more than the native type's edge in raw performance.
- **Three indexes — `user_id`, `status`, `applied_date`** — chosen directly from `DESIGN.md` §2's non-functional requirements table, which explicitly names all three as the columns queries need to be fast on. `user_id` is the big one: every single query this milestone's endpoints run filters on it, since ownership scoping (below) means there is no "list all applications" query, only "list *this user's* applications."

---

## `com.jobpulse.application` — the entity and its status enum

**`ApplicationStatus.java`** — a plain Java enum listing the same values allowed by the migration's `CHECK` constraint above:

```java
public enum ApplicationStatus {
    APPLIED, ONLINE_ASSESSMENT, PHONE_SCREEN, ONSITE, OFFER, REJECTED, WITHDRAWN
}
```

**`JobApplication.java`** — the entity, following the exact same shape M1's `User` entity established:

```java
@Entity
@Table(name = "job_applications")
public class JobApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    // jobTitle, jobUrl, location, source, notes similarly...

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApplicationStatus status;

    @Column(name = "applied_date")
    private LocalDate appliedDate;

    // createdAt/updatedAt + @PrePersist/@PreUpdate, same pattern as User

    // plain getters/setters
}
```

Two things worth explaining for anyone new to JPA:

- **`@Enumerated(EnumType.STRING)`.** Without this annotation, Hibernate defaults to storing an enum as its *ordinal* — the integer position in the enum declaration (`APPLIED` = 0, `ONLINE_ASSESSMENT` = 1, and so on). That's a trap: reorder the enum, or insert a new value anywhere but the end, and every existing row's meaning silently changes with no error from anyone. `EnumType.STRING` stores the literal name (`"PHONE_SCREEN"`) instead — slightly more storage, but human-readable in the database and immune to that entire class of bug. This is also *why* the migration's `CHECK` constraint lists the same string values — they have to match exactly.
- **`fetch = FetchType.LAZY` on the `@ManyToOne` to `User`.** Every query this milestone runs only ever needs the owning user's *ID* (for the ownership check, below) — never the full `User` row (email, password hash, full name). `LAZY` tells Hibernate not to bother joining to `users` and loading that data unless something actually calls `.getUser()` and touches a field on it. `EAGER` (the JPA default for `@ManyToOne`, easy to get by just omitting `fetch` entirely) would silently add an extra join to *every* application query, forever, for data nothing uses.

**`JobApplicationRepository.java`**:

```java
public interface JobApplicationRepository extends JpaRepository<JobApplication, Long> {
    List<JobApplication> findAllByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<JobApplication> findByIdAndUserId(Long id, Long userId);
}
```

`findByIdAndUserId` is the load-bearing method in this entire milestone. It's a single query doing double duty: "does this application exist" *and* "does it belong to this user," at the same time, in the same round-trip. The service layer (below) uses this everywhere a specific application needs to be fetched — get, update, delete — and treats "wrong owner" and "doesn't exist" as exactly the same outcome: an empty `Optional`, leading to the exact same 404. That's a deliberate security property, not an accident: an API that returned, say, `403 Forbidden` for "exists but isn't yours" versus `404 Not Found` for "doesn't exist at all" would let an attacker enumerate valid application IDs belonging to other users just by watching which status code comes back. One query, one code path, no leak.

---

## DTOs and the not-found exception

```java
public record JobApplicationRequest(
        @NotBlank String companyName,
        @NotBlank String jobTitle,
        String jobUrl,
        @NotNull ApplicationStatus status,
        LocalDate appliedDate,
        String location,
        String source,
        String notes
) {}
```

One request shape covers both `POST` (create) and `PUT` (update) — unlike some APIs that split these into `CreateRequest`/`UpdateRequest` variants, there was no field here that's only valid on one or the other, and `PUT` is a full replace (every field gets overwritten, not merged), so reusing the same record keeps things simple without losing anything.

`JobApplicationResponse` is the mirror-image output shape — same fields, plus `id`, `createdAt`, `updatedAt` — and, same as every DTO since M1, it's what actually leaves the backend. The `JobApplication` entity itself never gets serialized directly.

`JobApplicationNotFoundException` is a minimal marker exception, same pattern as M1's `EmailAlreadyInUseException` — its only job is being a distinct type `GlobalExceptionHandler` (below) can catch and map to `404`.

---

## `JobApplicationService.java` — ownership-scoped CRUD, and a real bug

```java
public List<JobApplicationResponse> listForUser(String email) {
    User user = getUser(email);
    return jobApplicationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
            .stream().map(this::toResponse).toList();
}

public JobApplicationResponse create(String email, JobApplicationRequest request) {
    User user = getUser(email);
    JobApplication application = new JobApplication();
    application.setUser(user);
    applyRequest(application, request);
    jobApplicationRepository.save(application);
    return toResponse(application);
}

public JobApplicationResponse get(String email, Long id) {
    JobApplication application = findOwned(email, id);
    return toResponse(application);
}

public JobApplicationResponse update(String email, Long id, JobApplicationRequest request) {
    JobApplication application = findOwned(email, id);
    applyRequest(application, request);
    JobApplication saved = jobApplicationRepository.save(application);
    return toResponse(saved);
}

public void delete(String email, Long id) {
    JobApplication application = findOwned(email, id);
    jobApplicationRepository.delete(application);
}

private JobApplication findOwned(String email, Long id) {
    User user = getUser(email);
    return jobApplicationRepository.findByIdAndUserId(id, user.getId())
            .orElseThrow(() -> new JobApplicationNotFoundException(id));
}
```

Every public method takes the caller's `email` as its first argument — never a `userId` the controller could get wrong, and never trusting anything from the request body about *whose* data this is. `getUser(email)` (a small private helper, same as `UserService.getCurrentUser` from M1) resolves that email to a real `User` row, and `findOwned` is the one place `findByIdAndUserId` gets called from — `get`, `update`, and `delete` all funnel through it, so the ownership check exists in exactly one place instead of being repeated three times with room for one copy to drift out of sync.

### The bug: a stale `updatedAt` in the update response

During end-to-end curl verification, `PUT /api/applications/{id}` was returning a response where `updatedAt` was identical to `createdAt`, even on a genuine update several seconds later. The database write itself was correct — a fresh `GET` afterward showed the right value — but the `PUT` response handed back a stale timestamp to the caller. Bad enough to be worth chasing down properly rather than shrugging it off, since a frontend trusting that response body would show the wrong "last updated" time.

**The cause is a JPA subtlety worth understanding, not just memorizing the fix.** `findOwned` calls a Spring Data repository method (`findByIdAndUserId`), and Spring Data wraps *each individual repository method call* in its own short transaction by default. That transaction commits and closes the moment `findOwned` returns — so by the time `update()`'s next line (`applyRequest(application, request)`) runs, the `application` object is **detached**: still a normal Java object with all its field values, but no longer tracked by Hibernate's persistence context. Mutating a detached object's fields doesn't do anything to the database on its own.

The original (buggy) code then called `jobApplicationRepository.save(application)` — and for an entity that already has a non-null ID, Spring Data's `save()` doesn't just "update in place." It calls `entityManager.merge(application)` internally, which **creates and returns a brand-new, separately-managed copy** with the detached object's field values copied over. The `@PreUpdate` lifecycle callback (which bumps `updatedAt = Instant.now()`) fires on *that new managed copy* during the merge/flush — not on the original `application` reference sitting in the `update()` method's local variable. The original bugged code ignored `save()`'s return value and built the response from the stale original:

```java
// before — bug
jobApplicationRepository.save(application);
return toResponse(application);  // application.updatedAt was never touched
```

```java
// after — fix
JobApplication saved = jobApplicationRepository.save(application);
return toResponse(saved);  // saved.updatedAt reflects the @PreUpdate callback
```

**The general lesson, worth remembering for any future service method that fetches-then-mutates an entity outside a `@Transactional` boundary:** once an entity crosses a transaction boundary (which, without an explicit `@Transactional` on the service method, happens after *every single* repository call), it's detached, and `save()` on a detached entity hands you back a different object than the one you passed in. Always build responses — or do anything that depends on lifecycle-callback side effects like `@PreUpdate` — from whatever `save()` *returns*, never from the pre-save reference. (The alternative fix would have been wrapping `update()` in `@Transactional`, keeping the entity managed for the whole method so mutations flush automatically without needing an explicit `save()` at all — a valid approach, but a new concept for this codebase; the explicit-`save()`-and-use-its-result style keeps consistency with how M1's `AuthService` was already written, so that's what we kept.)

---

## `JobApplicationController.java`

```java
@GetMapping
public List<JobApplicationResponse> list(@AuthenticationPrincipal UserDetails principal) {
    return jobApplicationService.listForUser(principal.getUsername());
}

@PostMapping
public ResponseEntity<JobApplicationResponse> create(
        @AuthenticationPrincipal UserDetails principal,
        @Valid @RequestBody JobApplicationRequest request
) {
    JobApplicationResponse response = jobApplicationService.create(principal.getUsername(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
}

// get, update follow the same @AuthenticationPrincipal + @PathVariable pattern

@DeleteMapping("/{id}")
public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
    jobApplicationService.delete(principal.getUsername(), id);
    return ResponseEntity.noContent().build();
}
```

Nothing new here conceptually from M1's `UserController.me()` — `@AuthenticationPrincipal UserDetails principal` pulls whoever `JwtAuthenticationFilter` authenticated earlier in the request, and `principal.getUsername()` (the user's email, per M1's `UserDetailsServiceImpl`) is threaded through to the service. `DELETE` returns `204 No Content` — the standard REST convention for "the operation succeeded and there's nothing meaningful to hand back."

One thing that *didn't* need to change: `SecurityConfig`. Its `authorizeHttpRequests` rule is `.anyRequest().authenticated()` for everything except the explicitly `permitAll`'d paths (`/api/auth/**`, actuator health/info, `/error`) — so `/api/applications/**` was protected automatically, the instant the controller existed, with zero security-layer code written this milestone.

---

## `GlobalExceptionHandler.java` — one new mapping

```java
@ExceptionHandler(JobApplicationNotFoundException.class)
public ResponseEntity<ErrorResponse> handleJobApplicationNotFound(JobApplicationNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(ex.getMessage()));
}
```

Slotted in alongside M1's existing handlers for duplicate email (409), bad credentials (401), and validation failures (400). Without this, `JobApplicationNotFoundException` — an unchecked `RuntimeException` — would propagate all the way up as a generic, unhelpful `500`.

---

## End-to-end verification

Same discipline as M1: nothing here was accepted as "done" until proven against a running instance via `curl`, including a scenario M1 had no way to test (there was only ever one kind of protected resource — "yourself" — so cross-user access simply couldn't come up until a second user's data existed to try reaching).

| Scenario | Expected | Got |
|---|---|---|
| Create → response has all fields + `id` | `201` | ✅ |
| List (one item created) | `200`, array of one | ✅ |
| Get by id | `200`, matches created data | ✅ |
| Update status `APPLIED` → `PHONE_SCREEN` | `200`, `updatedAt` > `createdAt` | ✅ (after the fix above — was showing a stale timestamp before) |
| Delete | `204` | ✅ |
| Get after delete | `404` | ✅ |
| Create with missing `companyName`/`jobTitle`/`status` | `400`, all three field errors listed | ✅ |
| List/create/etc with no `Authorization` header | `401` | ✅ |
| **User B `GET`s User A's application id** | `404` (not 403 — no existence leak) | ✅ |
| **User B `DELETE`s User A's application id** | `404`, and User A's record is provably untouched afterward | ✅ |
| User B's own list, before creating anything | `200`, empty array (not User A's data) | ✅ |
| `./mvnw test` (full context boot) | passes, now reports "Found 2 JPA repository interfaces" | ✅ |

That two-user isolation test is the one genuinely new kind of check this milestone — confirming not just that the happy path works, but that the ownership boundary `DESIGN.md` §6 calls "the #1 thing interviewers probe on a personal-data app" actually holds under a real cross-user attempt, not just in theory.

---

## What's next

M3 (Status history) per the roadmap: an `application_status_history` table (append-only, the source of truth for "how long did this sit in each stage"), a `PATCH /api/applications/{id}/status` endpoint kept deliberately separate from the generic `PUT` (changing status is a distinct business action worth its own audit trail, not just another field edit), and a timeline view. The frontend list/form deferred from this milestone is also still open — whichever comes first is a scoping conversation for next session, same as M2's backend/frontend split was.
