# Milestone 3 — Status History Walkthrough

_A junior-engineer-level walkthrough of everything Milestone 3 added: every new file, the one real API-contract change to existing M2 code, and how it was verified — backend and frontend both, done in a single pass this time. Companion to `docs/milestone-0.md` through `docs/milestone-2.md` — read those first if you haven't._

---

## What M3 delivers

Per `DESIGN.md`'s roadmap (§11): "Status history — `PATCH /status` endpoint, history table, timeline UI." Unlike M1 and M2, this milestone shipped backend and frontend together in one pass, at the user's explicit request — by this point the Flyway → entity → repository → DTO → service → controller → curl-verify → frontend → browser-verify rhythm from the first two milestones was well-established enough to run through without pausing for a mid-milestone scope check.

What's live by the end of this milestone:

- A real `application_status_history` table — append-only, the source of truth `DESIGN.md` §4 always intended it to be
- `PATCH /api/applications/{id}/status` — the dedicated, auditable way to change an application's status
- `GET /api/applications/{id}/history` — the timeline for one application, oldest first
- Creating an application now automatically records its initial status as the first history entry — the timeline starts complete, not empty
- **A real API-contract change to M2's `PUT /api/applications/{id}`: it can no longer change `status` at all**, even if a client sends one. This was flagged as a design decision before writing any code (see the M2→M3 planning conversation) and confirmed before implementation.
- A frontend timeline view and an inline status-change control, replacing the M2 edit-form status dropdown

---

## The migration — `V3__create_application_status_history_table.sql`

```sql
CREATE TABLE application_status_history (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES job_applications(id),
    status VARCHAR(30) NOT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT now(),
    note TEXT,
    CONSTRAINT chk_application_status_history_status CHECK (status IN (
        'APPLIED', 'ONLINE_ASSESSMENT', 'PHONE_SCREEN', 'ONSITE', 'OFFER', 'REJECTED', 'WITHDRAWN'
    ))
);

CREATE INDEX idx_application_status_history_application_id ON application_status_history(application_id);
```

`V3`, sitting alongside `V1` (users) and `V2` (job_applications) — same Flyway convention as before, nothing new here mechanically. Same `VARCHAR` + `CHECK` choice as `job_applications.status` in M2, for the same reason: avoiding a native Postgres `ENUM` type's `ALTER TYPE` migration overhead if the status list ever grows. This table has exactly one index beyond its primary key — `application_id`, since every query here is "give me this application's history," never a cross-application query.

---

## `ApplicationStatusHistory.java` — deliberately missing an update path

```java
@Entity
@Table(name = "application_status_history")
public class ApplicationStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ApplicationStatus status;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    @Column(columnDefinition = "TEXT")
    private String note;

    @PrePersist
    void onCreate() {
        changedAt = Instant.now();
    }

    // getters, and setters for application/status/note — no setChangedAt, no @PreUpdate
}
```

Same shape as `JobApplication` and `User` before it (`@ManyToOne(fetch = FetchType.LAZY)`, `@Enumerated(EnumType.STRING)` for the same ordinal-drift reasons M2 explained), but with one thing conspicuously absent: **no `@PreUpdate`, no `setChangedAt`, `changed_at` marked `updatable = false`.** Every other entity in this codebase has both a created and an updated timestamp because every other entity is a *mutable* record. This one isn't — nowhere in the service layer (below) is an `ApplicationStatusHistory` row ever fetched-then-modified, only ever created. The missing update machinery isn't an oversight; it's the type system backing up the "append-only" claim `DESIGN.md` §4 makes about this table.

`ApplicationStatusHistoryRepository` is one method: `findByApplicationIdOrderByChangedAtAsc`. Ascending, not descending — unlike M2's applications list (newest-created-first, because you're scanning for recent activity), a *timeline* reads naturally oldest-to-newest, the same direction time itself runs.

---

## The DTOs

```java
public record StatusChangeRequest(@NotNull ApplicationStatus status, String note) {}

public record StatusHistoryEntryResponse(Long id, ApplicationStatus status, Instant changedAt, String note) {}
```

`note` is optional on the request (no `@NotBlank`) — matching `DESIGN.md` §4's `note` column being nullable. A status change with no explanation is a completely valid, common case (most status changes in a job search don't come with a story worth writing down).

---

## The real change: `JobApplicationService.java`

This is where M3's one actual API-contract decision lives. Three methods changed or got added.

### `applyRequest` loses a line

M2's `applyRequest` (the helper shared by `create` and `update` to copy request fields onto the entity) used to include `application.setStatus(request.status())`. **That line is gone now.** `applyRequest` no longer touches `status` at all:

```java
private void applyRequest(JobApplication application, JobApplicationRequest request) {
    application.setCompanyName(request.companyName());
    application.setJobTitle(request.jobTitle());
    application.setJobUrl(request.jobUrl());
    application.setAppliedDate(request.appliedDate());
    application.setLocation(request.location());
    application.setSource(request.source());
    application.setNotes(request.notes());
}
```

Since `update()` calls `applyRequest` and nothing else, **`PUT /api/applications/{id}` can now structurally never change `status`** — not "the API rejects a status change with a 400," but "there is no code path left that would apply one," even if a client sends `status` in the request body (the field is still required for validation to pass, since `JobApplicationRequest` is shared with `create`, but its value is silently ignored on update). This is a stronger guarantee than a runtime check: a future contributor reading `applyRequest` six months from now can't accidentally "fix" a bug by adding a `setStatus` call back in without it being an obvious, deliberate diff.

### `create()` sets status explicitly, and records the first history entry

```java
@Transactional
public JobApplicationResponse create(String email, JobApplicationRequest request) {
    User user = getUser(email);
    JobApplication application = new JobApplication();
    application.setUser(user);
    application.setStatus(request.status());
    applyRequest(application, request);
    JobApplication saved = jobApplicationRepository.save(application);
    recordHistory(saved, saved.getStatus(), null);
    return toResponse(saved);
}
```

Because `applyRequest` no longer sets `status`, `create()` sets it directly, one line above the `applyRequest` call — the only place in the entire service where `status` is assigned from outside `changeStatus()` (below), and that's intentional: creating an application is the one legitimate way to pick an *initial* status without going through the dedicated status-change endpoint. Right after saving, `recordHistory(saved, saved.getStatus(), null)` writes the first history row — so a freshly created application's timeline is never empty; it starts with exactly one entry showing whatever status it was created with, note `null` since there's nothing to say yet.

### `changeStatus()` — new, and the actual `PATCH` handler's logic

```java
@Transactional
public JobApplicationResponse changeStatus(String email, Long id, StatusChangeRequest request) {
    JobApplication application = findOwned(email, id);
    application.setStatus(request.status());
    JobApplication saved = jobApplicationRepository.save(application);
    recordHistory(saved, request.status(), request.note());
    return toResponse(saved);
}

private void recordHistory(JobApplication application, ApplicationStatus status, String note) {
    ApplicationStatusHistory entry = new ApplicationStatusHistory();
    entry.setApplication(application);
    entry.setStatus(status);
    entry.setNote(note);
    statusHistoryRepository.save(entry);
}
```

Two writes, two different tables (`job_applications.status` gets updated; a brand-new row lands in `application_status_history`), and they need to succeed or fail together — a status update that "succeeded" but left no history trace (or a history entry pointing at a status the application was never actually set to) would violate the entire point of this milestone. That's what `@Transactional` is for here, and it's the **first appearance of `@Transactional` in this codebase.** M1 and M2 got by on Spring Data's default per-repository-call transactions plus the explicit-`save()`-and-use-its-result pattern the M2 bugfix established, because every write in those milestones touched exactly one row in one table. `create()` picked up `@Transactional` too, for the same reason (application row + history row, one unit). Every other service method — `get`, `update`, `delete`, `getHistory`, `listForUser` — still doesn't need it, and doesn't have it. `@Transactional` earned its place here by being the smallest fix for a specific, real atomicity requirement, not applied blanket "to be safe."

`changeStatus()` deliberately records a new history row on *every* call, even if the new status happens to match the current one — no special-casing "did it actually change." Simpler code, and an idempotent-looking status re-set is still a real timestamped event worth having a record of (e.g., confirming "yes, still onsite stage" after a delay).

### `getHistory()` — the timeline endpoint's backing method

```java
public List<StatusHistoryEntryResponse> getHistory(String email, Long id) {
    JobApplication application = findOwned(email, id);
    return statusHistoryRepository.findByApplicationIdOrderByChangedAtAsc(application.getId())
            .stream()
            .map(this::toHistoryResponse)
            .toList();
}
```

Routes through `findOwned` — the exact same ownership-scoped lookup `get`, `update`, and `delete` already used since M2 — so a user requesting another user's application's history gets the identical `404` as any other cross-user attempt on this resource, not a new, different failure mode to reason about.

---

## `JobApplicationController.java` — two new mappings

```java
@PatchMapping("/{id}/status")
public JobApplicationResponse changeStatus(
        @AuthenticationPrincipal UserDetails principal,
        @PathVariable Long id,
        @Valid @RequestBody StatusChangeRequest request
) {
    return jobApplicationService.changeStatus(principal.getUsername(), id, request);
}

@GetMapping("/{id}/history")
public List<StatusHistoryEntryResponse> history(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
    return jobApplicationService.getHistory(principal.getUsername(), id);
}
```

`PATCH`, not `PUT` or `POST` — HTTP's semantic distinction actually matters here and `DESIGN.md` §6 calls it out explicitly: `PUT` means "replace this resource's representation," `PATCH` means "apply a partial modification." Changing just the status field, as a distinct business action with its own side effect (a history row), is exactly what `PATCH` is for. No `SecurityConfig` or `GlobalExceptionHandler` changes were needed — both new endpoints fall under the same `anyRequest().authenticated()` rule as everything else in `/api/applications/**`, and `changeStatus`'s validation failures and `findOwned`'s 404s reuse handlers M1 and M2 already wired up.

---

## Backend verification

Same curl discipline as every milestone before this one — plus a scenario unique to M3: proving a *removed* capability stays removed, not just that new capabilities work.

| Scenario | Expected | Got |
|---|---|---|
| Create application with `status: APPLIED` | `201`; `GET .../history` immediately shows exactly 1 entry (`APPLIED`, `note: null`) | ✅ |
| `PATCH .../status` to `PHONE_SCREEN` with a note | `200`, application's `status` field updates | ✅ |
| `GET .../history` after that PATCH | 2 entries, ascending: `APPLIED` then `PHONE_SCREEN` (note attached) | ✅ |
| `PUT` the same application, sending `"status":"REJECTED"` in the body alongside an unrelated field change | `200`; **`status` stays `PHONE_SCREEN`** — the sneaky status value in the payload is silently ignored | ✅ |
| `GET .../history` after that `PUT` | **Still exactly 2 entries** — the `PUT` recorded nothing | ✅ |
| A second user `PATCH`es the first user's application status | `404`, identical to any other cross-user attempt | ✅ |
| A second user `GET`s the first user's application history | `404` | ✅ |
| `PATCH .../status` with no `status` field | `400`, `"status: must not be null"` | ✅ |
| `./mvnw test` | passes, now reports "Found 3 JPA repository interfaces" | ✅ |

The `PUT`-with-a-sneaky-status-field test is the one that actually proves the M3 design decision holds, not just that the new endpoints work in isolation — it directly attacks the exact thing that would make the history table's audit trail worthless if it failed.

---

## The frontend

### API layer — two new functions

```js
export function changeApplicationStatus(id, status, note) {
  return client.patch(`/applications/${id}/status`, { status, note }).then((res) => res.data)
}

export function getApplicationHistory(id) {
  return client.get(`/applications/${id}/history`).then((res) => res.data)
}
```

Thin wrappers, same pattern as every other function in `api/applications.js` since M2.

### `ApplicationForm.jsx` — the status field disappears when editing

```jsx
{!initial && (
  <label>
    Status *
    <select value={form.status} onChange={(e) => update('status', e.target.value)} required>
      {/* ... */}
    </select>
  </label>
)}
```

One conditional wraps the status `<select>` that M2 rendered unconditionally: it now only appears when `initial` is `null` — i.e., only in create mode. When editing, `form.status` is still present in the component's local state (carried over from `{ ...emptyForm, ...initial }`, same as before), so the `PUT` payload still includes a valid `status` value and satisfies the backend's `@NotNull` validation — there's just no visible control for changing it, since changing it wouldn't do anything now anyway. Showing an editable field that silently no-ops would be a worse UX than not showing it at all.

### `ApplicationList.jsx` — status becomes an inline, immediately-acting control

M2's static `<span className="status-badge">` became a `<select>`:

```jsx
<select
  className={`status-select status-${app.status.toLowerCase()}`}
  value={app.status}
  onChange={(e) => onStatusChange(app, e.target.value)}
>
  {APPLICATION_STATUSES.map((s) => (
    <option key={s} value={s}>{formatStatus(s)}</option>
  ))}
</select>
```

Picking a new value fires the `PATCH` immediately — no separate "save" step, no confirmation dialog. That's a deliberate UX call: unlike deleting an application (M2 kept a `window.confirm` for that), a status change is cheap to reverse (just pick the old value again) and happens often enough in normal use that a confirmation step would just be friction. A new "History" button sits next to the existing Edit/Delete pair.

### `StatusHistoryModal.jsx` — new component

Fetches `getApplicationHistory(application.id)` on mount and renders the ascending list — status badge, formatted timestamp, and the note if one was left. Uses a `cancelled` flag inside its `useEffect` (a standard React defensive pattern) so a fetch that resolves after the modal's already been closed doesn't try to call `setState` on a component that no longer cares about the result.

### Verification

Extended the same throwaway Playwright-driver approach M2's frontend used (no project-level browser-testing skill exists yet — see `docs/milestone-2.md`'s note on that): register → create an application → open History (assert exactly 1 entry) → change status via the inline dropdown → open History again (assert exactly 2 entries) → open Edit (assert **zero** status-labeled form fields exist in it) → make an unrelated edit → confirm the status dropdown still shows the value set by the earlier `PATCH`, untouched by the edit. Zero console errors, zero uncaught page errors. Screenshots reviewed directly — the populated history modal (two colored status badges, timestamps, correct chronological order) rendered exactly as designed.

---

## What's next

Per the roadmap: M4 (Reminders — data layer only, no email yet), then M5 (the scheduler + Mailhog integration `DESIGN.md` §9 spends the most words on, since idempotent claiming under concurrent/restarted job runs is flagged as "the trickiest correctness issue in the whole system"). Both are backend-heavy; whether the frontend keeps pace in the same pass (as M3 did) or falls a milestone behind (as M2's did) is a call to make when M4 starts, same as every milestone before it.
