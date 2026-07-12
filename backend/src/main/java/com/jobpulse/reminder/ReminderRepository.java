package com.jobpulse.reminder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByApplicationIdOrderByRemindAtAsc(Long applicationId);

    /** Traverses reminder -> application -> user, since a reminder has no user_id of its own. */
    Optional<Reminder> findByIdAndApplication_User_Id(Long id, Long userId);

    /**
     * JOIN FETCH pulls the application and its user into memory in this one
     * query, so the scheduler can read recipient/company/job-title later
     * without triggering a lazy-load outside an open session.
     */
    @Query("SELECT r FROM Reminder r JOIN FETCH r.application a JOIN FETCH a.user "
            + "WHERE r.status = com.jobpulse.reminder.ReminderStatus.PENDING AND r.remindAt <= :now "
            + "ORDER BY r.remindAt ASC")
    List<Reminder> findDueForSending(@Param("now") Instant now);

    /**
     * The idempotency-critical step: atomically flips PENDING -> PROCESSING
     * for exactly one row, and only if it's still PENDING. Returns the
     * number of rows updated (0 or 1) -- 0 means something else (an
     * overlapping run) already claimed it first, and the caller must skip it.
     */
    @Modifying
    @Query("UPDATE Reminder r SET r.status = com.jobpulse.reminder.ReminderStatus.PROCESSING "
            + "WHERE r.id = :id AND r.status = com.jobpulse.reminder.ReminderStatus.PENDING")
    int claim(@Param("id") Long id);

    @Modifying
    @Query("UPDATE Reminder r SET r.status = com.jobpulse.reminder.ReminderStatus.SENT, r.sentAt = :sentAt WHERE r.id = :id")
    void markSent(@Param("id") Long id, @Param("sentAt") Instant sentAt);

    @Modifying
    @Query("UPDATE Reminder r SET r.status = com.jobpulse.reminder.ReminderStatus.FAILED WHERE r.id = :id")
    void markFailed(@Param("id") Long id);
}
