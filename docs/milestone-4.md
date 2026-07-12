# Milestone 4 — Reminders (Data Layer) Walkthrough

_A junior-engineer-level walkthrough of everything Milestone 4 added, and a real regression bug — latent since M3 — that this milestone's testing finally exposed. Companion to `docs/milestone-0.md` through `docs/milestone-3.md` — read those first if you haven't._

---

## What M4 delivers

`DESIGN.md`'s roadmap (§11) scopes M4 as "Reminders (data layer) — Reminder CRUD, no email yet." Unlike M2 and M3, the roadmap table doesn't list any frontend deliverable for this milestone at all — the reminder UI presumably lands alongside M5's scheduler work or M6's dashboard, not here. So this one was built backend-only without needing to ask, unlike M2's explicit backend/frontend scoping conversation.

What's live by the end of this milestone:

- A real `reminders` table
- `POST /api/applications/{id}/reminders` — create a reminder for one of your own applications
- `GET /api/applications/{id}/reminders` — list them, soonest first
- `DELETE /api/reminders/{id}` — cancel one (a genuine row delete, not a status flag — `DESIGN.md`'s `ReminderStatus` enum only has `PENDING`/`SENT`/`FAILED`, no `CANCELLED`)
- Every reminder starts and stays `PENDING` — nothing in this milestone ever transitions that status; the scheduled job that does (`SENT` on success, `FAILED` on failure) is explicitly M5's job
- A regression fix to M3's schema, found by this milestone's own verification (see below)

---

## The migration — `V4__create_reminders_table.sql`

```sql
CREATE TABLE reminders (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES job_applications(id) ON DELETE CASCADE,
    remind_at TIMESTAMP NOT NULL,
    message VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    sent_at TIMESTAMP,
    CONSTRAINT chk_reminders_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);

CREATE INDEX idx_reminders_application_id ON reminders(application_id);
CREATE INDEX idx_reminders_remind_at ON reminders(remind_at);
```

Two things worth calling out, both small deviations from the established M2/M3 pattern, both deliberate:

- **No `created_at`/`updated_at` columns.** Every other table so far has them. This one doesn't, because `DESIGN.md` §4's `reminders` schema table doesn't list them — followed exactly as specified rather than adding timestamps nobody asked for. `remind_at` and `sent_at` already carry all the time information this table needs.
- **`ON DELETE CASCADE` on the FK, from the very first version of this table** (not bolted on later, unlike `application_status_history` — see below). A reminder with no application to remind you about is meaningless; if the application goes, its reminders should go with it. `remind_at` and `application_id` are both indexed — `application_id` for the ownership-scoped list query every read here uses, `remind_at` because `DESIGN.md` §4 explicitly flags it: "scheduler queries on this." The scheduler itself doesn't exist until M5, but the query pattern it'll use is already fixed by the schema, so the index earns its keep now.

---

## `com.jobpulse.reminder` — new feature package

Matches `DESIGN.md` §10's planned folder structure exactly (`reminder/ → entity, repository, service, controller, scheduler` — the `scheduler` piece is M5's addition, everything else is this milestone's).

**`ReminderStatus.java`** — `PENDING, SENT, FAILED`. A comment on the enum spells out the state machine `DESIGN.md` §5 describes (`PENDING → SENT` or `PENDING → FAILED`) even though nothing in this milestone's code implements either transition yet — every reminder created through this API starts and stays `PENDING`.

**`Reminder.java`** — same shape as `JobApplication`/`ApplicationStatusHistory` before it: `@ManyToOne(fetch = FetchType.LAZY)` to `JobApplication` (never the full `User` — a reminder only needs its application's identity, and only needs it to resolve back to a user for ownership checks, below), `@Enumerated(EnumType.STRING)` for `status` (same ordinal-drift protection M2/M3 already established). No `@PrePersist`/`@PreUpdate` — there's nothing here to timestamp automatically, matching the migration's decision to skip `created_at`/`updated_at`.

**`ReminderRepository.java`**:

```java
public interface ReminderRepository extends JpaRepository<Reminder, Long> {
    List<Reminder> findByApplicationIdOrderByRemindAtAsc(Long applicationId);
    Optional<Reminder> findByIdAndApplication_User_Id(Long id, Long userId);
}
```

The second method is worth pausing on. Every ownership check so far (`JobApplication.findByIdAndUserId`) has been one hop: the entity has a direct `user_id` column. A `Reminder` doesn't — it only knows its `application_id`, and ownership has to travel two hops: reminder → application → user. Spring Data supports this via underscore-separated property-path traversal in a derived query method name: `Application_User_Id` reads as "traverse the `application` association, then its `user` association, then compare `id`." Without the underscores, Spring Data would instead look for a single flat property literally named `applicationUserId` on `Reminder` and fail to start up. This one method is what makes `DELETE /api/reminders/{id}` — a top-level endpoint with no `/applications/{id}/` prefix to anchor ownership through the URL — safe to expose at all.

**DTOs**: `ReminderRequest(remindAt, message)` and `ReminderResponse(id, applicationId, remindAt, message, status, sentAt)`. One judgment call not explicitly mandated by `DESIGN.md`: `remindAt` carries a `@Future` validation constraint. A reminder for a date that's already passed doesn't serve any purpose this system supports yet — it's not "schedule a reminder to fire immediately," just a plain data-entry mistake — so rejecting it with a clear `400` beats silently accepting a reminder the M5 scheduler would immediately (and confusingly) try to fire the moment it's built.

**`ReminderNotFoundException.java`** — same minimal-marker-exception pattern as `JobApplicationNotFoundException` and `EmailAlreadyInUseException`, wired into `GlobalExceptionHandler` for a `404`.

---

## `ReminderService.java` — ownership through the application, not directly

```java
public List<ReminderResponse> listForApplication(String email, Long applicationId) {
    JobApplication application = findOwnedApplication(email, applicationId);
    return reminderRepository.findByApplicationIdOrderByRemindAtAsc(application.getId())
            .stream().map(this::toResponse).toList();
}

public ReminderResponse create(String email, Long applicationId, ReminderRequest request) {
    JobApplication application = findOwnedApplication(email, applicationId);
    Reminder reminder = new Reminder();
    reminder.setApplication(application);
    reminder.setRemindAt(request.remindAt());
    reminder.setMessage(request.message());
    reminder.setStatus(ReminderStatus.PENDING);
    Reminder saved = reminderRepository.save(reminder);
    return toResponse(saved);
}

public void delete(String email, Long reminderId) {
    User user = getUser(email);
    Reminder reminder = reminderRepository.findByIdAndApplication_User_Id(reminderId, user.getId())
            .orElseThrow(() -> new ReminderNotFoundException(reminderId));
    reminderRepository.delete(reminder);
}

private JobApplication findOwnedApplication(String email, Long applicationId) {
    User user = getUser(email);
    return jobApplicationRepository.findByIdAndUserId(applicationId, user.getId())
            .orElseThrow(() -> new JobApplicationNotFoundException(applicationId));
}
```

Notice `create` and `listForApplication` reuse `JobApplicationRepository` directly (a cross-feature dependency: `com.jobpulse.reminder` depending on a repository from `com.jobpulse.application`) rather than going through `JobApplicationService`. This is a deliberate layering choice: `JobApplicationService`'s methods return DTOs meant for HTTP responses, not entities meant for further business logic — reaching into another feature's *repository* for a plain ownership-scoped entity lookup is the normal, expected shape of cross-feature dependency in this codebase (the same pattern `JobApplicationService` itself already uses with `UserRepository` since M2). Reaching into another feature's *service* to get back a DTO you'd then have to unwrap would be the wrong direction.

`findOwnedApplication` throws `JobApplicationNotFoundException` (M2's exception, not a new reminder-specific one) when the parent application doesn't exist or isn't yours — correct, since from the caller's perspective "the application you're trying to attach a reminder to" is exactly what's missing, and reusing the existing exception means `GlobalExceptionHandler` needed zero new wiring for that case. `delete()` is the one method that can't route through `findOwnedApplication` at all, since it only has a `reminderId`, not an `applicationId` — that's exactly what `findByIdAndApplication_User_Id` exists for.

---

## `ReminderController.java` — two path families, one controller

```java
@PostMapping("/api/applications/{applicationId}/reminders")
@GetMapping("/api/applications/{applicationId}/reminders")
@DeleteMapping("/api/reminders/{id}")
```

No class-level `@RequestMapping`, unlike every other controller so far — `JobApplicationController`'s `@RequestMapping("/api/applications")` works because every one of its routes shares that prefix. This controller's routes don't: create/list nest under an application (`DESIGN.md` §6 lists them that way deliberately, since a reminder only makes sense in the context of one application), while delete is top-level (`/api/reminders/{id}`, matching the spec's REST table exactly, not `/api/applications/{id}/reminders/{reminderId}`). Full paths on each method, no shortcuts.

---

## The bug: M3 left a landmine, M4 stepped on it

End-to-end verification for this milestone included the obvious "does deleting the parent application clean up its reminders" check — and along the way, deleting the application itself came back `500 Internal Server Error`, not the expected `204`:

```
org.postgresql.util.PSQLException: ERROR: update or delete on table "job_applications"
violates foreign key constraint "application_status_history_application_id_fkey"
on table "application_status_history"
  Detail: Key (id)=(8) is still referenced from table "application_status_history".
```

**The bug is entirely in M3's migration, not this one's.** `V3__create_application_status_history_table.sql` created `application_status_history.application_id REFERENCES job_applications(id)` with no `ON DELETE CASCADE` — so Postgres, correctly, refused to let a `job_applications` row be deleted while any history row still pointed at it. And since M3's `create()` method auto-records an initial history entry for *every* application (that was M3's whole point — the timeline should never start empty), this meant **every application, without exception, blocked its own deletion** from the moment M3 shipped.

This went completely unnoticed through the entirety of M3 for a simple reason: M2's own delete-verification curl commands ran *before* M3's `application_status_history` table existed. Nothing re-ran that test after M3 landed, because M3's own verification (`docs/milestone-3.md`) focused on the new status-change behavior, not on re-checking old M2 endpoints. M4 was the first milestone whose testing plan happened to include "delete an application, confirm its children are cleaned up" — and that's what surfaced a bug that had been sitting there for an entire milestone.

**The fix couldn't just edit `V3`.** Flyway checksums every migration file the moment it's applied; if `V3`'s content changed, Flyway would refuse to start up on *any* environment that had already run the original `V3` (including this development database), reporting a checksum mismatch. The only correct fix is a new migration:

```sql
-- V5__cascade_delete_application_status_history.sql
ALTER TABLE application_status_history
    DROP CONSTRAINT application_status_history_application_id_fkey,
    ADD CONSTRAINT application_status_history_application_id_fkey
        FOREIGN KEY (application_id) REFERENCES job_applications(id) ON DELETE CASCADE;
```

After applying `V5`, the exact same delete-an-application-with-history request that previously 500'd returned a clean `204`, and a fresh create-application → create-reminder → delete-application → confirm-reminder-is-gone-too sequence worked end-to-end with no manual cleanup needed on either child table.

**The general lesson:** every foreign key added to a table that hangs off `job_applications` (or `users`) needs an explicit, conscious decision about `ON DELETE` behavior *at the moment the migration is written* — "what happens to these rows if their parent is deleted" isn't a question that can be safely deferred to "whenever someone happens to test that path." `reminders`' own FK in this same milestone got `ON DELETE CASCADE` correctly from its very first version, specifically because this bug was fresh in mind while writing it.

---

## Verification

| Scenario | Expected | Got |
|---|---|---|
| Create reminder with a future `remindAt` | `201` | ✅ |
| Create reminder with a past `remindAt` | `400`, `"remindAt: must be a future date"` | ✅ |
| Create reminder with no `remindAt` | `400`, `"remindAt: must not be null"` | ✅ |
| List reminders for an application | `200`, correct array, `status: PENDING` | ✅ |
| A second user lists/creates reminders on the first user's application | `404` (the *application* isn't found for them — correct, since they can't see it exists at all) | ✅ |
| A second user `DELETE`s the first user's reminder directly by ID | `404` | ✅ |
| Owner deletes their own reminder | `204`, then list is empty | ✅ |
| Delete an application that has reminders and status history (before `V5`) | `500` ❌ — the bug above | fixed by `V5` |
| Delete an application that has reminders and status history (after `V5`) | `204`; both the reminder and the history rows are gone, confirmed by re-querying | ✅ |
| `./mvnw test` | passes, 5 migrations validated | ✅ |

---

## What's next

M5 per the roadmap: the `@Scheduled` polling job that actually fires reminder emails, the `EmailSender` interface with a Mailhog-backed local implementation, and — the part `DESIGN.md` §9 spends the most words on — making the "claim a due reminder" step idempotent, so a reminder is never sent twice even if the job overlaps itself or the app restarts mid-batch. Genuinely the trickiest correctness problem in this whole system, by the design doc's own description.
