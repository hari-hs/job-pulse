package com.jobpulse.reminder;

public class ReminderNotFoundException extends RuntimeException {

    public ReminderNotFoundException(Long id) {
        super("Reminder not found: " + id);
    }
}
