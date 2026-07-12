package com.jobpulse.application.dto;

import com.jobpulse.application.ApplicationStatus;

import java.time.Instant;
import java.time.LocalDate;

public record JobApplicationResponse(
        Long id,
        String companyName,
        String jobTitle,
        String jobUrl,
        ApplicationStatus status,
        LocalDate appliedDate,
        String location,
        String source,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
