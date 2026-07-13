package com.jobpulse.application;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends JpaRepository<JobApplication, Long>, JpaSpecificationExecutor<JobApplication> {

    /** Unfiltered, unpaginated -- used by DashboardService, which aggregates over everything. */
    List<JobApplication> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<JobApplication> findByIdAndUserId(Long id, Long userId);
}
