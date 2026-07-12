package com.jobpulse.reminder;

import com.jobpulse.application.JobApplication;
import com.jobpulse.email.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls for due reminders and emails them. See DESIGN.md §9: the
 * correctness-critical property is that a reminder is never emailed
 * twice, even if this method's next run overlaps a slow previous one, or
 * the app restarts mid-batch.
 *
 * The claim step (ReminderRepository.claim -- an atomic conditional
 * UPDATE ... WHERE status = PENDING) is what provides that guarantee: only
 * one caller can ever win the claim for a given reminder, because Postgres
 * serializes concurrent UPDATEs to the same row and re-evaluates the WHERE
 * clause after any blocking write commits. A run that loses the race
 * simply sees 0 rows affected and skips that reminder -- it does not retry
 * or error.
 *
 * What this does NOT fully solve: if the process crashes after claiming
 * (PROCESSING) but before the send completes and gets marked SENT/FAILED,
 * that reminder is stuck in PROCESSING with no automatic recovery. That's
 * a deliberate scope cut -- DESIGN.md's stated requirement is "never sent
 * twice," not "always eventually sent," and building automatic recovery
 * for a crash in that narrow window is exactly the kind of complexity
 * ShedLock/SQS would exist to solve later (DESIGN.md §9), not something
 * this milestone needs.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService reminderService;
    private final EmailSender emailSender;

    public ReminderScheduler(ReminderService reminderService, EmailSender emailSender) {
        this.reminderService = reminderService;
        this.emailSender = emailSender;
    }

    @Scheduled(fixedDelayString = "${reminder.scheduler.fixed-delay-ms}")
    public void sendDueReminders() {
        List<Reminder> due = reminderService.findDueForSending();
        for (Reminder reminder : due) {
            processReminder(reminder);
        }
    }

    private void processReminder(Reminder reminder) {
        if (!reminderService.claim(reminder.getId())) {
            log.debug("Reminder {} already claimed by another run, skipping", reminder.getId());
            return;
        }

        try {
            JobApplication application = reminder.getApplication();
            String recipient = application.getUser().getEmail();
            String subject = "Reminder: %s at %s".formatted(application.getJobTitle(), application.getCompanyName());

            StringBuilder body = new StringBuilder("This is a reminder to follow up on your ")
                    .append(application.getJobTitle())
                    .append(" application at ")
                    .append(application.getCompanyName())
                    .append(".");
            if (reminder.getMessage() != null && !reminder.getMessage().isBlank()) {
                body.append("\n\nNote: ").append(reminder.getMessage());
            }

            emailSender.send(recipient, subject, body.toString());
            reminderService.markSent(reminder.getId());
        } catch (Exception e) {
            log.warn("Failed to send reminder {}: {}", reminder.getId(), e.getMessage());
            reminderService.markFailed(reminder.getId());
        }
    }
}
