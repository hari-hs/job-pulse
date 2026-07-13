package com.jobpulse.dashboard;

import com.jobpulse.application.ApplicationStatus;
import com.jobpulse.application.JobApplication;
import com.jobpulse.application.JobApplicationRepository;
import com.jobpulse.dashboard.dto.DashboardSummaryResponse;
import com.jobpulse.dashboard.dto.WeeklyTrendPoint;
import com.jobpulse.user.User;
import com.jobpulse.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    /** How far back the weekly trend looks, including the current week. */
    private static final int TREND_WEEKS = 12;

    private final JobApplicationRepository jobApplicationRepository;
    private final UserRepository userRepository;

    public DashboardService(JobApplicationRepository jobApplicationRepository, UserRepository userRepository) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.userRepository = userRepository;
    }

    public DashboardSummaryResponse getSummary(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email));
        List<JobApplication> applications = jobApplicationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());

        return new DashboardSummaryResponse(
                applications.size(),
                statusBreakdown(applications),
                responseRate(applications),
                weeklyTrend(applications)
        );
    }

    private Map<ApplicationStatus, Long> statusBreakdown(List<JobApplication> applications) {
        Map<ApplicationStatus, Long> breakdown = new EnumMap<>(ApplicationStatus.class);
        for (ApplicationStatus status : ApplicationStatus.values()) {
            breakdown.put(status, 0L);
        }
        for (JobApplication application : applications) {
            breakdown.merge(application.getStatus(), 1L, Long::sum);
        }
        return breakdown;
    }

    /** Any status other than APPLIED means something happened after the initial application. */
    private double responseRate(List<JobApplication> applications) {
        if (applications.isEmpty()) {
            return 0.0;
        }
        long responded = applications.stream()
                .filter(a -> a.getStatus() != ApplicationStatus.APPLIED)
                .count();
        return (double) responded / applications.size();
    }

    private List<WeeklyTrendPoint> weeklyTrend(List<JobApplication> applications) {
        LocalDate currentWeekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate earliestWeekStart = currentWeekStart.minusWeeks(TREND_WEEKS - 1L);

        Map<LocalDate, Long> counts = new HashMap<>();
        for (JobApplication application : applications) {
            LocalDate appliedDate = application.getAppliedDate();
            if (appliedDate == null) {
                continue;
            }
            LocalDate weekStart = appliedDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            if (weekStart.isBefore(earliestWeekStart) || weekStart.isAfter(currentWeekStart)) {
                continue;
            }
            counts.merge(weekStart, 1L, Long::sum);
        }

        List<WeeklyTrendPoint> trend = new ArrayList<>(TREND_WEEKS);
        for (LocalDate week = earliestWeekStart; !week.isAfter(currentWeekStart); week = week.plusWeeks(1)) {
            trend.add(new WeeklyTrendPoint(week, counts.getOrDefault(week, 0L)));
        }
        return trend;
    }
}
