package com.jobpulse.reminder;

import com.jobpulse.application.JobApplication;
import com.jobpulse.application.JobApplicationNotFoundException;
import com.jobpulse.application.JobApplicationRepository;
import com.jobpulse.reminder.dto.ReminderRequest;
import com.jobpulse.reminder.dto.ReminderResponse;
import com.jobpulse.user.User;
import com.jobpulse.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ReminderService {

    private final ReminderRepository reminderRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final UserRepository userRepository;

    public ReminderService(
            ReminderRepository reminderRepository,
            JobApplicationRepository jobApplicationRepository,
            UserRepository userRepository
    ) {
        this.reminderRepository = reminderRepository;
        this.jobApplicationRepository = jobApplicationRepository;
        this.userRepository = userRepository;
    }

    public List<ReminderResponse> listForApplication(String email, Long applicationId) {
        JobApplication application = findOwnedApplication(email, applicationId);
        return reminderRepository.findByApplicationIdOrderByRemindAtAsc(application.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ReminderResponse create(String email, Long applicationId, ReminderRequest request) {
        JobApplication application = findOwnedApplication(email, applicationId);
        Reminder reminder = new Reminder();
        reminder.setApplication(application);
        reminder.setRemindAt(request.remindAt());
        reminder.setMessage(request.message());
        reminder.setStatus(ReminderStatus.PENDING);
        Reminder saved = reminderRepository.save(reminder);
        return toResponse(saved);
    }

    public void delete(String email, Long reminderId) {
        User user = getUser(email);
        Reminder reminder = reminderRepository.findByIdAndApplication_User_Id(reminderId, user.getId())
                .orElseThrow(() -> new ReminderNotFoundException(reminderId));
        reminderRepository.delete(reminder);
    }

    /** Reminders due now, with their application/user already loaded -- for the scheduler only, not exposed via HTTP. */
    public List<Reminder> findDueForSending() {
        return reminderRepository.findDueForSending(Instant.now());
    }

    /** Attempts to claim a due reminder. False means another run already claimed it -- not an error, just skip it. */
    @Transactional
    public boolean claim(Long reminderId) {
        return reminderRepository.claim(reminderId) == 1;
    }

    @Transactional
    public void markSent(Long reminderId) {
        reminderRepository.markSent(reminderId, Instant.now());
    }

    @Transactional
    public void markFailed(Long reminderId) {
        reminderRepository.markFailed(reminderId);
    }

    private JobApplication findOwnedApplication(String email, Long applicationId) {
        User user = getUser(email);
        return jobApplicationRepository.findByIdAndUserId(applicationId, user.getId())
                .orElseThrow(() -> new JobApplicationNotFoundException(applicationId));
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email));
    }

    private ReminderResponse toResponse(Reminder reminder) {
        return new ReminderResponse(
                reminder.getId(),
                reminder.getApplication().getId(),
                reminder.getRemindAt(),
                reminder.getMessage(),
                reminder.getStatus(),
                reminder.getSentAt()
        );
    }
}
