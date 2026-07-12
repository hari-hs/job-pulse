-- PROCESSING is the "claimed but not yet finalized" state the idempotent
-- scheduler needs (DESIGN.md §9): a reminder atomically moves PENDING ->
-- PROCESSING before the email is sent, so two overlapping job runs (or a
-- restart mid-batch) can never both send the same reminder. V4 didn't
-- anticipate this state, so the CHECK constraint has to be widened here.
ALTER TABLE reminders
    DROP CONSTRAINT chk_reminders_status,
    ADD CONSTRAINT chk_reminders_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED'));
