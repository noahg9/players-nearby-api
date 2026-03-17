package com.pickupsports.session.job;

import com.pickupsports.session.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SessionCompletionJob {

    private static final Logger log = LoggerFactory.getLogger(SessionCompletionJob.class);

    private final SessionRepository sessionRepository;

    public SessionCompletionJob(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Scheduled(cron = "0 */15 * * * *")  // every 15 minutes on the clock
    public void markExpiredSessions() {
        try {
            int count = sessionRepository.markExpiredSessionsCompleted();
            if (count > 0) {
                log.info("Marked {} session(s) as completed", count);
            }
        } catch (Exception e) {
            log.warn("Failed to mark expired sessions as completed", e);
        }
    }
}
