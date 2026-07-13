package com.jobpulse.dashboard.dto;

import java.time.LocalDate;

/** weekStart is always a Monday. */
public record WeeklyTrendPoint(LocalDate weekStart, long count) {
}
