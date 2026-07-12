package com.jobpulse.reminder;

/**
 * PENDING -> PROCESSING -> SENT, or PENDING -> PROCESSING -> FAILED.
 * PROCESSING is the scheduler's atomic claim state (see ReminderScheduler):
 * a reminder is only ever moved out of PENDING by winning a conditional
 * UPDATE, which is what makes concurrent/overlapping job runs safe.
 */
public enum ReminderStatus {
    PENDING,
    PROCESSING,
    SENT,
    FAILED
}
