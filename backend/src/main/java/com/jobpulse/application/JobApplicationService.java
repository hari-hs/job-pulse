package com.jobpulse.application;

import com.jobpulse.application.dto.JobApplicationRequest;
import com.jobpulse.application.dto.JobApplicationResponse;
import com.jobpulse.application.dto.StatusChangeRequest;
import com.jobpulse.application.dto.StatusHistoryEntryResponse;
import com.jobpulse.common.PageResponse;
import com.jobpulse.user.User;
import com.jobpulse.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
public class JobApplicationService {

    private final JobApplicationRepository jobApplicationRepository;
    private final ApplicationStatusHistoryRepository statusHistoryRepository;
    private final UserRepository userRepository;

    public JobApplicationService(
            JobApplicationRepository jobApplicationRepository,
            ApplicationStatusHistoryRepository statusHistoryRepository,
            UserRepository userRepository
    ) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.statusHistoryRepository = statusHistoryRepository;
        this.userRepository = userRepository;
    }

    private static final int MAX_PAGE_SIZE = 100;

    public PageResponse<JobApplicationResponse> search(
            String email,
            ApplicationStatus status,
            String company,
            LocalDate dateFrom,
            LocalDate dateTo,
            Pageable pageable
    ) {
        User user = getUser(email);

        Specification<JobApplication> spec = JobApplicationSpecifications.ownedBy(user.getId());
        if (status != null) {
            spec = spec.and(JobApplicationSpecifications.hasStatus(status));
        }
        if (company != null && !company.isBlank()) {
            spec = spec.and(JobApplicationSpecifications.companyContains(company));
        }
        if (dateFrom != null) {
            spec = spec.and(JobApplicationSpecifications.appliedOnOrAfter(dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and(JobApplicationSpecifications.appliedOnOrBefore(dateTo));
        }

        if (pageable.getPageSize() > MAX_PAGE_SIZE) {
            pageable = PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }

        Page<JobApplication> page = jobApplicationRepository.findAll(spec, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional
    public JobApplicationResponse create(String email, JobApplicationRequest request) {
        User user = getUser(email);
        JobApplication application = new JobApplication();
        application.setUser(user);
        application.setStatus(request.status());
        applyRequest(application, request);
        JobApplication saved = jobApplicationRepository.save(application);
        recordHistory(saved, saved.getStatus(), null);
        return toResponse(saved);
    }

    public JobApplicationResponse get(String email, Long id) {
        JobApplication application = findOwned(email, id);
        return toResponse(application);
    }

    /**
     * Status is deliberately not part of this update — it can only change
     * via {@link #changeStatus}, which also records a history entry.
     * A generic PUT that silently changed status would let the history
     * table drift out of sync with job_applications.status.
     */
    public JobApplicationResponse update(String email, Long id, JobApplicationRequest request) {
        JobApplication application = findOwned(email, id);
        applyRequest(application, request);
        JobApplication saved = jobApplicationRepository.save(application);
        return toResponse(saved);
    }

    @Transactional
    public JobApplicationResponse changeStatus(String email, Long id, StatusChangeRequest request) {
        JobApplication application = findOwned(email, id);
        application.setStatus(request.status());
        JobApplication saved = jobApplicationRepository.save(application);
        recordHistory(saved, request.status(), request.note());
        return toResponse(saved);
    }

    public List<StatusHistoryEntryResponse> getHistory(String email, Long id) {
        JobApplication application = findOwned(email, id);
        return statusHistoryRepository.findByApplicationIdOrderByChangedAtAsc(application.getId())
                .stream()
                .map(this::toHistoryResponse)
                .toList();
    }

    public void delete(String email, Long id) {
        JobApplication application = findOwned(email, id);
        jobApplicationRepository.delete(application);
    }

    private void recordHistory(JobApplication application, ApplicationStatus status, String note) {
        ApplicationStatusHistory entry = new ApplicationStatusHistory();
        entry.setApplication(application);
        entry.setStatus(status);
        entry.setNote(note);
        statusHistoryRepository.save(entry);
    }

    private JobApplication findOwned(String email, Long id) {
        User user = getUser(email);
        return jobApplicationRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new JobApplicationNotFoundException(id));
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email));
    }

    private void applyRequest(JobApplication application, JobApplicationRequest request) {
        application.setCompanyName(request.companyName());
        application.setJobTitle(request.jobTitle());
        application.setJobUrl(request.jobUrl());
        application.setAppliedDate(request.appliedDate());
        application.setLocation(request.location());
        application.setSource(request.source());
        application.setNotes(request.notes());
    }

    private JobApplicationResponse toResponse(JobApplication application) {
        return new JobApplicationResponse(
                application.getId(),
                application.getCompanyName(),
                application.getJobTitle(),
                application.getJobUrl(),
                application.getStatus(),
                application.getAppliedDate(),
                application.getLocation(),
                application.getSource(),
                application.getNotes(),
                application.getCreatedAt(),
                application.getUpdatedAt()
        );
    }

    private StatusHistoryEntryResponse toHistoryResponse(ApplicationStatusHistory entry) {
        return new StatusHistoryEntryResponse(entry.getId(), entry.getStatus(), entry.getChangedAt(), entry.getNote());
    }
}
