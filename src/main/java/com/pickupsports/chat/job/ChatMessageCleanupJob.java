package com.pickupsports.chat.job;

import com.pickupsports.chat.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class ChatMessageCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageCleanupJob.class);
    private static final int RETENTION_DAYS = 7;

    private final ChatMessageRepository chatMessageRepository;

    public ChatMessageCleanupJob(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")  // daily at 03:00
    public void deleteExpiredMessages() {
        try {
            Instant cutoff = Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS);
            int count = chatMessageRepository.deleteForSessionsEndedBefore(cutoff);
            if (count > 0) {
                log.info("Deleted {} chat message(s) for sessions ended more than {} days ago",
                    count, RETENTION_DAYS);
            }
        } catch (Exception e) {
            log.warn("Failed to clean up expired chat messages", e);
        }
    }
}
