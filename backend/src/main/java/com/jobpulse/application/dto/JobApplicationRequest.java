package com.jobpulse.application.dto;

import com.jobpulse.application.ApplicationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record JobApplicationRequest(
        @NotBlank String companyName,
        @NotBlank String jobTitle,
        String jobUrl,
        @NotNull ApplicationStatus status,
        LocalDate appliedDate,
        String location,
        String source,
        String notes
) {
}
