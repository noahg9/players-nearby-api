package com.pickupsports.chat.service;

import com.pickupsports.auth.service.RateLimiterService;
import com.pickupsports.chat.domain.ChatMessage;
import com.pickupsports.chat.repository.ChatMessageRepository;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.session.domain.Participant;
import com.pickupsports.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RateLimiterService rateLimiterService;

    public ChatService(SessionRepository sessionRepository,
                       ParticipantRepository participantRepository,
                       UserRepository userRepository,
                       ChatMessageRepository chatMessageRepository,
                       RateLimiterService rateLimiterService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.chatMessageRepository = chatMessageRepository;
        this.rateLimiterService = rateLimiterService;
    }

    public ChatMessage postMessage(UUID sessionId, UUID userId, String guestToken, String content) {
        sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        Participant participant;
        String rateLimitKey;

        if (userId != null) {
            participant = participantRepository.findBySessionIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a joined participant"));
            rateLimitKey = userId.toString();
        } else if (guestToken != null && !guestToken.isBlank()) {
            participant = participantRepository.findBySessionIdAndGuestToken(sessionId, guestToken)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You are not a joined participant"));
            rateLimitKey = guestToken;
        } else {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        if (!"joined".equals(participant.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a joined participant");
        }

        if (!rateLimiterService.tryConsumeChat(rateLimitKey)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many messages. Try again later.");
        }

        String authorName;
        if (userId != null) {
            authorName = userRepository.findById(userId)
                .map(u -> u.name())
                .orElse("Unknown");
        } else {
            authorName = participant.guestName();
        }

        ChatMessage message = new ChatMessage(
            UUID.randomUUID(),
            sessionId,
            userId,
            authorName,
            content,
            Instant.now()
        );

        return chatMessageRepository.save(message);
    }

    public record MessagesPage(List<ChatMessage> content, int total, int page, int size) {}

    public MessagesPage getMessages(UUID sessionId, int page, int size) {
        sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        List<ChatMessage> messages = chatMessageRepository.findBySessionId(sessionId, page, size);
        int total = chatMessageRepository.countBySessionId(sessionId);
        return new MessagesPage(messages, total, page, size);
    }
}
