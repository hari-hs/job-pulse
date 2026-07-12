package com.jobpulse.email;

/**
 * The reminder scheduler depends on this interface, never on a concrete
 * mail implementation. Locally, {@link SmtpEmailSender} points at Mailhog.
 * A production profile would add an SES-backed implementation and select
 * it via Spring profile -- the scheduler's code would not change at all.
 */
public interface EmailSender {

    void send(String to, String subject, String body);
}
