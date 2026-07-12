package com.jobpulse.reminder;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findByApplicationIdOrderByRemindAtAsc(Long applicationId);

    /** Traverses reminder -> application -> user, since a reminder has no user_id of its own. */
    Optional<Reminder> findByIdAndApplication_User_Id(Long id, Long userId);
}
