package com.jobpulse.reminder.dto;

import com.jobpulse.reminder.ReminderStatus;

import java.time.Instant;

public record ReminderResponse(
        Long id,
        Long applicationId,
        Instant remindAt,
        String message,
        ReminderStatus status,
        Instant sentAt
) {
}
