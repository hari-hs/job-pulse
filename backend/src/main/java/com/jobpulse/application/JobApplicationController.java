package com.jobpulse.application;

import com.jobpulse.application.dto.JobApplicationRequest;
import com.jobpulse.application.dto.JobApplicationResponse;
import com.jobpulse.application.dto.StatusChangeRequest;
import com.jobpulse.application.dto.StatusHistoryEntryResponse;
import com.jobpulse.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/applications")
public class JobApplicationController {

    private final JobApplicationService jobApplicationService;

    public JobApplicationController(JobApplicationService jobApplicationService) {
        this.jobApplicationService = jobApplicationService;
    }

    @GetMapping
    public PageResponse<JobApplicationResponse> list(
            @AuthenticationPrincipal UserDetails principal,
            @RequestParam(required = false) ApplicationStatus status,
            @RequestParam(required = false) String company,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        return jobApplicationService.search(principal.getUsername(), status, company, dateFrom, dateTo, pageable);
    }

    @PostMapping
    public ResponseEntity<JobApplicationResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @Valid @RequestBody JobApplicationRequest request
    ) {
        JobApplicationResponse response = jobApplicationService.create(principal.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}")
    public JobApplicationResponse get(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        return jobApplicationService.get(principal.getUsername(), id);
    }

    @PutMapping("/{id}")
    public JobApplicationResponse update(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @Valid @RequestBody JobApplicationRequest request
    ) {
        return jobApplicationService.update(principal.getUsername(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        jobApplicationService.delete(principal.getUsername(), id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public JobApplicationResponse changeStatus(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long id,
            @Valid @RequestBody StatusChangeRequest request
    ) {
        return jobApplicationService.changeStatus(principal.getUsername(), id, request);
    }

    @GetMapping("/{id}/history")
    public List<StatusHistoryEntryResponse> history(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        return jobApplicationService.getHistory(principal.getUsername(), id);
    }
}
