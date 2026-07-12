package com.jobpulse.reminder;

/**
 * PENDING -> SENT or PENDING -> FAILED, driven by the scheduled email job
 * that arrives in M5. Every reminder created through this milestone's API
 * starts and stays PENDING -- nothing in M4 transitions it.
 */
public enum ReminderStatus {
    PENDING,
    SENT,
    FAILED
}
