package com.jobpulse.application;

import com.jobpulse.application.dto.JobApplicationRequest;
import com.jobpulse.application.dto.JobApplicationResponse;
import com.jobpulse.user.User;
import com.jobpulse.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class JobApplicationService {

    private final JobApplicationRepository jobApplicationRepository;
    private final UserRepository userRepository;

    public JobApplicationService(JobApplicationRepository jobApplicationRepository, UserRepository userRepository) {
        this.jobApplicationRepository = jobApplicationRepository;
        this.userRepository = userRepository;
    }

    public List<JobApplicationResponse> listForUser(String email) {
        User user = getUser(email);
        return jobApplicationRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public JobApplicationResponse create(String email, JobApplicationRequest request) {
        User user = getUser(email);
        JobApplication application = new JobApplication();
        application.setUser(user);
        applyRequest(application, request);
        jobApplicationRepository.save(application);
        return toResponse(application);
    }

    public JobApplicationResponse get(String email, Long id) {
        JobApplication application = findOwned(email, id);
        return toResponse(application);
    }

    public JobApplicationResponse update(String email, Long id, JobApplicationRequest request) {
        JobApplication application = findOwned(email, id);
        applyRequest(application, request);
        JobApplication saved = jobApplicationRepository.save(application);
        return toResponse(saved);
    }

    public void delete(String email, Long id) {
        JobApplication application = findOwned(email, id);
        jobApplicationRepository.delete(application);
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
        application.setStatus(request.status());
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
}
