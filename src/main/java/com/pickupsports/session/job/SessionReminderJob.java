package com.pickupsports.session.job;

import com.pickupsports.auth.service.EmailService;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class SessionReminderJob {

    private static final Logger log = LoggerFactory.getLogger(SessionReminderJob.class);

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final EmailService emailService;

    public SessionReminderJob(SessionRepository sessionRepository,
                              ParticipantRepository participantRepository,
                              EmailService emailService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.emailService = emailService;
    }

    // Runs every 15 minutes. Window is 55–70 min from now so each session is caught by exactly one run.
    @Scheduled(cron = "0 */15 * * * *")
    public void sendReminders() {
        try {
            Instant now = Instant.now();
            Instant windowStart = now.plus(55, ChronoUnit.MINUTES);
            Instant windowEnd = now.plus(70, ChronoUnit.MINUTES);

            List<Session> sessions = sessionRepository.findSessionsStartingBetween(windowStart, windowEnd);
            if (sessions.isEmpty()) return;

            log.info("Sending 1-hour reminders for {} session(s)", sessions.size());

            for (Session session : sessions) {
                List<String> emails = participantRepository.findJoinedParticipantEmails(session.id());
                for (String email : emails) {
                    emailService.sendSessionReminder(
                        email, session.title(), session.startTime(), session.locationName());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send session reminders", e);
        }
    }
}
