package com.jobpulse.application.dto;

import com.jobpulse.application.ApplicationStatus;

import java.time.Instant;

public record StatusHistoryEntryResponse(
        Long id,
        ApplicationStatus status,
        Instant changedAt,
        String note
) {
}
