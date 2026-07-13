package com.jobpulse.application;

import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

/**
 * Building blocks for the filtered GET /api/applications query (M7). Each
 * method returns one predicate; JobApplicationService combines only the
 * ones whose filter value was actually supplied, so an all-null filter set
 * degrades to "just the ownership check" -- the same query M2 always ran.
 */
public final class JobApplicationSpecifications {

    private JobApplicationSpecifications() {
    }

    public static Specification<JobApplication> ownedBy(Long userId) {
        return (root, query, cb) -> cb.equal(root.get("user").get("id"), userId);
    }

    public static Specification<JobApplication> hasStatus(ApplicationStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Case-insensitive substring match -- "search," not exact filter. */
    public static Specification<JobApplication> companyContains(String company) {
        return (root, query, cb) -> cb.like(cb.lower(root.get("companyName")), "%" + company.toLowerCase() + "%");
    }

    public static Specification<JobApplication> appliedOnOrAfter(LocalDate date) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("appliedDate"), date);
    }

    public static Specification<JobApplication> appliedOnOrBefore(LocalDate date) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("appliedDate"), date);
    }
}
