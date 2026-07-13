package com.jobpulse.dashboard.dto;

import com.jobpulse.application.ApplicationStatus;

import java.util.List;
import java.util.Map;

public record DashboardSummaryResponse(
        long totalApplications,
        Map<ApplicationStatus, Long> statusBreakdown,
        double responseRate,
        List<WeeklyTrendPoint> weeklyTrend
) {
}
