package com.pickupsports.session.service;

import com.pickupsports.session.domain.Participant;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
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

    public ParticipantService(SessionRepository sessionRepository,
                              ParticipantRepository participantRepository) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
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
        String status = joinedCount < session.capacity() ? "joined" : "waitlist";

        Participant participant = new Participant(
            UUID.randomUUID(), sessionId, userId,
            null, null,
            status, Instant.now(),
            null
        );
        participantRepository.save(participant);

        return new JoinResult(status);
    }

    @Transactional
    public GuestJoinResult guestJoin(UUID sessionId, String guestName) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        validateOpenForJoining(session);

        int joinedCount = participantRepository.countJoined(sessionId);
        String status = joinedCount < session.capacity() ? "joined" : "waitlist";
        String guestToken = UUID.randomUUID().toString();

        Participant participant = new Participant(
            UUID.randomUUID(), sessionId, null,
            guestName, guestToken,
            status, Instant.now(),
            guestName
        );
        participantRepository.save(participant);

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
            promoteOldestWaitlisted(sessionId);
        }
    }

    @Transactional
    public void guestLeave(UUID sessionId, String guestToken) {
        Participant participant = participantRepository.findBySessionIdAndGuestToken(sessionId, guestToken)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown guest token"));

        participantRepository.delete(participant.id());

        if ("joined".equals(participant.status())) {
            promoteOldestWaitlisted(sessionId);
        }
    }

    private void promoteOldestWaitlisted(UUID sessionId) {
        participantRepository.findOldestWaitlisted(sessionId)
            .ifPresent(p -> participantRepository.updateStatus(p.id(), "joined"));
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
