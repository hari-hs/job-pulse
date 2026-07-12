package com.jobpulse.reminder;

import com.jobpulse.reminder.dto.ReminderRequest;
import com.jobpulse.reminder.dto.ReminderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @PostMapping("/api/applications/{applicationId}/reminders")
    public ResponseEntity<ReminderResponse> create(
            @AuthenticationPrincipal UserDetails principal,
            @PathVariable Long applicationId,
            @Valid @RequestBody ReminderRequest request
    ) {
        ReminderResponse response = reminderService.create(principal.getUsername(), applicationId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/api/applications/{applicationId}/reminders")
    public List<ReminderResponse> list(@AuthenticationPrincipal UserDetails principal, @PathVariable Long applicationId) {
        return reminderService.listForApplication(principal.getUsername(), applicationId);
    }

    @DeleteMapping("/api/reminders/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal UserDetails principal, @PathVariable Long id) {
        reminderService.delete(principal.getUsername(), id);
        return ResponseEntity.noContent().build();
    }
}
