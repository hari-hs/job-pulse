# Milestone 5 — Scheduler + Local Email Walkthrough

_A junior-engineer-level walkthrough of the milestone `DESIGN.md` §9 calls out by name as the hardest correctness problem in the whole system. Companion to `docs/milestone-0.md` through `docs/milestone-4.md` — read those first if you haven't._

---

## What M5 delivers

`DESIGN.md`'s roadmap (§11): "Scheduler + local email — `@Scheduled` job, idempotent claim logic, Mailhog integration." No frontend line for M5 either (same as M4), so this is backend-only again.

Before this milestone, a reminder created in M4 just sat in the database — nothing ever looked at it. After this milestone:

- A background job checks every 5 minutes (configurable) for reminders whose time has come
- It actually sends an email for each one, through a local fake SMTP server (Mailhog) so you can see the email in a browser without any real mail account
- Each reminder is marked `SENT` (with a timestamp) or `FAILED` afterward, so it's never processed again
- **The hard part, done properly**: a reminder can never be emailed twice, even if two poll cycles somehow overlap or the app restarts mid-batch

---

## Mailhog — the "local replacement for SES" from `DESIGN.md` §12

```yaml
# docker-compose.yml
  mailhog:
    image: mailhog/mailhog:v1.0.1
    container_name: jobpulse-mailhog
    ports:
      - "1025:1025" # SMTP -- the backend sends here
      - "8025:8025" # Web UI -- open in a browser to see caught emails
```

Mailhog speaks real SMTP on `1025` — nothing about the code sending to it needs to know it isn't a real mail server — and exposes a web UI (and a small HTTP API) on `8025` where every email it receives just sits, readable, instead of actually leaving the machine. `DESIGN.md` §12 chose it specifically for this: same protocol as the real thing, zero external account needed, one Docker container.

---

## `com.jobpulse.email` — the interface `DESIGN.md` §8 asked for by name

```java
public interface EmailSender {
    void send(String to, String subject, String body);
}
```

```java
@Service
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public SmtpEmailSender(JavaMailSender mailSender, @Value("${jobpulse.mail.from}") String fromAddress) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
```

`DESIGN.md` §8 point 3 is explicit that the scheduler should depend on "an `EmailSender` interface — not a concrete SMTP or SES class." The payoff of that indirection: when a production profile eventually needs a `SesEmailSender` (AWS SES, per `DESIGN.md` §12's migration notes), it's a second class implementing the same one-method interface, selected via Spring profile — `ReminderScheduler` (below) never changes, never even needs to know which implementation is active. `SmtpEmailSender` itself is thin on purpose: it wraps Spring's `JavaMailSender` (auto-configured the moment `spring-boot-starter-mail` is on the classpath and `spring.mail.host`/`port` are set — the same pattern M1 already established for the datasource) in the exact three-argument shape the interface promises.

One naming note for the M1-established Boot-4-gotcha-radar: `spring-boot-starter-mail` is **unchanged** from Boot 3 — unlike Flyway, this one didn't get split into a differently-named starter. Worth checking each time regardless (the BOM, not assumption, settled this one).

---

## Config — `application.yml`

```yaml
spring:
  mail:
    host: ${MAIL_HOST:localhost}
    port: ${MAIL_PORT:1025}

jobpulse:
  mail:
    from: ${MAIL_FROM:noreply@jobpulse.local}

reminder:
  scheduler:
    fixed-delay-ms: ${REMINDER_SCHEDULER_FIXED_DELAY_MS:300000} # 5 minutes, per DESIGN.md §9
```

Same `${VAR:default}` pattern as every other piece of config since M1 — locally, everything resolves to Mailhog's port and a five-minute poll with zero environment setup; a real deployment overrides `MAIL_HOST`/`MAIL_PORT` to a real SMTP relay (or, with a `SesEmailSender` added later, ignores these entirely) via env vars alone.

`com.jobpulse.config.SchedulingConfig` is a two-line class whose only job is `@EnableScheduling` — Spring doesn't run any `@Scheduled` method unless *something* in the app carries this annotation. `DESIGN.md` §10 planned this exact class name in `config/` alongside `SecurityConfig`, so that's where it went.

---

## The idempotency design — the actual point of this milestone

`DESIGN.md` §9, verbatim: "the query that fetches due reminders should atomically transition them from `PENDING` to an in-progress state... before sending, so two overlapping runs can't both grab the same row." That single sentence is the design this milestone had to implement correctly.

### Why a new status, `PROCESSING`

`ReminderStatus` grew a fourth value:

```java
public enum ReminderStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
```

`PENDING → PROCESSING → SENT` or `PENDING → PROCESSING → FAILED`. `PROCESSING` is the "in-progress state" `DESIGN.md` §9 asks for by description, even though the original §5 entity description only named `PENDING`/`SENT`/`FAILED` — the idempotency mechanism the design doc itself specifies *implies* this fourth state, so adding it is filling in a detail the spec left implicit, not deviating from it.

Since M4's `V4` migration already shipped its `CHECK (status IN ('PENDING', 'SENT', 'FAILED'))` constraint, and Flyway checksums applied migrations (the same reason M4's cascade-delete fix needed a new `V5` rather than editing `V3`), this needed its own migration:

```sql
-- V6__add_processing_status_to_reminders.sql
ALTER TABLE reminders
    DROP CONSTRAINT chk_reminders_status,
    ADD CONSTRAINT chk_reminders_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'));
```

### The claim — one atomic conditional UPDATE

```java
@Modifying
@Query("UPDATE Reminder r SET r.status = com.jobpulse.reminder.ReminderStatus.PROCESSING "
        + "WHERE r.id = :id AND r.status = com.jobpulse.reminder.ReminderStatus.PENDING")
int claim(@Param("id") Long id);
```

This is the entire idempotency guarantee, in one SQL statement. It returns the number of rows it actually updated — `1` if this call won the claim, `0` if the reminder wasn't `PENDING` anymore (someone else already claimed it). The safety doesn't come from application code checking-then-updating in two steps (that has an obvious race: two threads could both check "is it PENDING?", both see yes, both proceed) — it comes from Postgres itself. If two callers somehow ran this exact `UPDATE` against the same row at the same moment, Postgres serializes them: the first to arrive takes a row lock and commits its change; the second blocks until that commit finishes, then re-evaluates its own `WHERE status = 'PENDING'` clause against the now-current row — which is `PROCESSING`, not `PENDING` anymore — and matches zero rows. **The database's own concurrency control is the mechanism, not anything the application does.**

**Why not the `SELECT ... FOR UPDATE SKIP LOCKED` alternative `DESIGN.md` §9 also mentions?** That approach holds a row lock open for the duration of one transaction while the slow part (the actual network call to send an email) happens inside it — a well-known anti-pattern: never hold a database lock open across a slow external I/O call, since anything else waiting on that lock (including, eventually, ordinary reads in some isolation levels) stalls for as long as the network call takes. The conditional-`UPDATE`-and-check-the-count approach claims the row in a fast, self-contained transaction, then does the slow email-sending work with no lock held at all.

### The full flow — `ReminderScheduler.java`

```java
@Scheduled(fixedDelayString = "${reminder.scheduler.fixed-delay-ms}")
public void sendDueReminders() {
    List<Reminder> due = reminderService.findDueForSending();
    for (Reminder reminder : due) {
        processReminder(reminder);
    }
}

private void processReminder(Reminder reminder) {
    if (!reminderService.claim(reminder.getId())) {
        return; // lost the race -- not an error, just move on
    }
    try {
        // ... build subject/body from reminder.getApplication() ...
        emailSender.send(recipient, subject, body.toString());
        reminderService.markSent(reminder.getId());
    } catch (Exception e) {
        log.warn("Failed to send reminder {}: {}", reminder.getId(), e.getMessage());
        reminderService.markFailed(reminder.getId());
    }
}
```

Three deliberate choices worth explaining:

- **`findDueForSending` uses `JOIN FETCH`**, not a plain `WHERE status = PENDING AND remindAt <= :now`:
  ```java
  @Query("SELECT r FROM Reminder r JOIN FETCH r.application a JOIN FETCH a.user "
          + "WHERE r.status = ... AND r.remindAt <= :now ORDER BY r.remindAt ASC")
  List<Reminder> findDueForSending(@Param("now") Instant now);
  ```
  Building the email needs the recipient's address (`reminder.getApplication().getUser().getEmail()`) and the job details (`getJobTitle()`, `getCompanyName()`) — all `@ManyToOne(fetch = LAZY)` associations. Without `JOIN FETCH`, touching those fields later (after the query's own transaction has closed) would either throw a `LazyInitializationException` or silently trigger extra per-row queries, depending on session state. `JOIN FETCH` loads everything in one round trip, so the data is safe to read from plain Java memory for the rest of `processReminder`, transaction or no transaction.
- **A `try`/`catch` around each reminder individually**, inside the loop — not one `try` around the whole `sendDueReminders()` method. One reminder failing to send (bad address, Mailhog briefly down) must not stop every *other* due reminder in the same batch from being processed. Catching broadly (`Exception`, not some narrower mail-specific type) is deliberate here too: whatever goes wrong, the response is the same — mark this one `FAILED`, keep going.
- **Claiming happens before the `try` block, not inside it.** If `claim()` itself somehow failed, there'd be nothing to mark `FAILED` — a row that lost the claim race isn't this run's responsibility at all, so it's just skipped with an early `return`, not routed through the failure path.

### What this design deliberately does not solve

If the process crashes in the narrow window after `claim()` succeeds (row is now `PROCESSING`) but before `markSent`/`markFailed` runs — say, the JVM is killed mid-`emailSender.send()` — that reminder is left stuck in `PROCESSING` forever, with no automatic recovery built here. That's a conscious scope cut, not an oversight: `DESIGN.md` §9's stated requirement is "a reminder is never emailed twice... even if the app restarts mid-run" — and that guarantee still holds, since a stuck `PROCESSING` row is never picked up again by `findDueForSending` (which only matches `PENDING`), so it can *never* be double-sent. What it can't guarantee is that every reminder is *eventually* sent no matter what. Solving that fully is exactly what `DESIGN.md` §9 names ShedLock or a real SQS-based redesign for, later, if this ever runs as more than one instance — not something a single local instance needs to solve today.

---

## End-to-end verification, with a real email

Every prior milestone verified via HTTP status codes and response bodies. This one needed one more layer: proof that an actual email left the application and landed somewhere, not just that the database rows changed correctly.

**Setup**: `docker compose up -d postgres mailhog`, backend started with `REMINDER_SCHEDULER_FIXED_DELAY_MS=10000` (10 seconds, only for this verification run — the shipped default stays the `DESIGN.md`-specified 5 minutes; a real 5-minute wait isn't a reasonable thing to sit through by hand every time this gets tested).

| Step | Expected | Got |
|---|---|---|
| Clear Mailhog's inbox (`DELETE :8025/api/v1/messages`) | empty | ✅ |
| Create a reminder ~4 seconds in the future | `201`, `status: PENDING` | ✅ |
| Wait 20s (past due date + one 10s poll cycle) | — | — |
| `GET` the reminder via our own API | `status: SENT`, `sentAt` populated | ✅ |
| `GET :8025/api/v2/messages` | exactly 1 message, correct `To`, `Subject: "Reminder: ML Engineer at Cyberdyne Systems"`, body includes the reminder's optional note | ✅ |
| Wait one more full poll cycle (15s) | message count **still 1** — no duplicate send of an already-`SENT` reminder | ✅ |
| Create a reminder 1 hour in the future, wait one poll cycle | still `PENDING` — not due yet, correctly left alone | ✅ |
| `./mvnw test` (full context boot, now including the `JavaMailSender` bean and `@EnableScheduling`) | passes | ✅ |

The "wait one more cycle, confirm still exactly 1 message" step is the one that actually demonstrates the idempotency property in practice, not just in theory — a `SENT` reminder is structurally invisible to `findDueForSending`'s `WHERE status = PENDING` clause, so there's no code path left that could re-send it.

---

## What's next

M6 (Dashboard) per the roadmap: an aggregate-stats endpoint (counts by status, response rate, applications-per-week trend) and simple charts on the frontend — the first milestone since M3 with an explicit frontend line in `DESIGN.md`'s own roadmap table, and the first one that's mostly about read-side aggregation queries rather than another CRUD slice.
