package com.pickupsports.session.service;

import com.pickupsports.auth.service.EmailService;
import com.pickupsports.session.domain.Participant;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
public class ParticipantService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public ParticipantService(SessionRepository sessionRepository,
                              ParticipantRepository participantRepository,
                              UserRepository userRepository,
                              EmailService emailService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    public record JoinResult(String status) {}

    public record GuestJoinResult(String status, String guestToken) {}

    @Transactional
    public JoinResult join(UUID sessionId, UUID userId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        validateOpenForJoining(session);

        participantRepository.findBySessionIdAndUserId(sessionId, userId)
            .ifPresent(p -> {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "You are already a participant of this session");
            });

        int joinedCount = participantRepository.countJoined(sessionId);
        String status = joinedCount < session.capacity() - session.offlineCount() ? "joined" : "waitlist";

        Participant participant = new Participant(
            UUID.randomUUID(), sessionId, userId,
            null, null,
            status, Instant.now(),
            null
        );
        participantRepository.save(participant);

        if ("joined".equals(status) && !userId.equals(session.hostUserId())) {
            userRepository.findById(userId).ifPresent(joiner ->
                notifyHostOfJoin(session, joiner.name())
            );
        }

        return new JoinResult(status);
    }

    @Transactional
    public GuestJoinResult guestJoin(UUID sessionId, String guestName) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        validateOpenForJoining(session);

        int joinedCount = participantRepository.countJoined(sessionId);
        String status = joinedCount < session.capacity() - session.offlineCount() ? "joined" : "waitlist";
        String guestToken = UUID.randomUUID().toString();

        Participant participant = new Participant(
            UUID.randomUUID(), sessionId, null,
            guestName, guestToken,
            status, Instant.now(),
            guestName
        );
        participantRepository.save(participant);

        if ("joined".equals(status)) {
            notifyHostOfJoin(session, guestName);
        }

        return new GuestJoinResult(status, guestToken);
    }

    @Transactional
    public void leave(UUID sessionId, UUID userId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (userId.equals(session.hostUserId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Hosts cannot leave — cancel the session instead");
        }

        Participant participant = participantRepository.findBySessionIdAndUserId(sessionId, userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "You are not a participant of this session"));

        participantRepository.delete(participant.id());

        if ("joined".equals(participant.status())) {
            promoteOldestWaitlisted(sessionId, session);
        }
    }

    @Transactional
    public void guestLeave(UUID sessionId, String guestToken) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        Participant participant = participantRepository.findBySessionIdAndGuestToken(sessionId, guestToken)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown guest token"));

        participantRepository.delete(participant.id());

        if ("joined".equals(participant.status())) {
            promoteOldestWaitlisted(sessionId, session);
        }
    }

    private void promoteOldestWaitlisted(UUID sessionId, Session session) {
        participantRepository.findOldestWaitlisted(sessionId).ifPresent(promoted -> {
            participantRepository.updateStatus(promoted.id(), "joined");
            if (promoted.userId() != null) {
                userRepository.findById(promoted.userId()).ifPresent(user ->
                    emailService.sendWaitlistPromotion(
                        user.email(), session.title(), session.startTime(), session.locationName())
                );
            }
        });
    }

    private void notifyHostOfJoin(Session session, String joinerName) {
        userRepository.findById(session.hostUserId()).ifPresent(host ->
            emailService.sendHostJoinNotification(host.email(), joinerName, session.title())
        );
    }

    /**
     * Validates that the session is open for new participants.
     * Extension point: add visibility checks (private/invite_only) here when those features land.
     */
    private void validateOpenForJoining(Session session) {
        if (!session.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Session is not available for joining");
        }
    }
}
