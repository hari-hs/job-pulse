package com.jobpulse.application.dto;

import com.jobpulse.application.ApplicationStatus;
import jakarta.validation.constraints.NotNull;

public record StatusChangeRequest(
        @NotNull ApplicationStatus status,
        String note
) {
}
