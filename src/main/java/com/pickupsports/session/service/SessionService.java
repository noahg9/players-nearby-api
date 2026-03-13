package com.pickupsports.session.service;

import com.pickupsports.auth.service.EmailService;
import com.pickupsports.session.domain.Participant;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.user.domain.User;
import com.pickupsports.user.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public SessionService(SessionRepository sessionRepository,
                          ParticipantRepository participantRepository,
                          UserRepository userRepository,
                          EmailService emailService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Transactional
    public Session createSession(UUID hostUserId, String sport, String title, String notes,
                                 Instant startTime, Instant endTime, int capacity,
                                 double lat, double lng, String locationName) {
        UUID sessionId = UUID.randomUUID();
        sessionRepository.save(sessionId, sport, title, notes, startTime, endTime,
                               capacity, hostUserId, lat, lng, locationName);
        Participant hostParticipant = new Participant(
            UUID.randomUUID(), sessionId, hostUserId, null, null,
            "joined", Instant.now(), null
        );
        participantRepository.save(hostParticipant);
        return sessionRepository.findById(sessionId).orElseThrow();
    }

    @Transactional
    public Session updateSession(UUID callerId, UUID sessionId, String title, String notes,
                                 Instant startTime, Instant endTime, Integer capacity) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (!callerId.equals(session.hostUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can edit this session");
        }
        if (!session.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session is not active");
        }
        if (capacity != null) {
            int joinedCount = participantRepository.countJoined(sessionId);
            if (capacity < joinedCount) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Capacity cannot be less than current number of joined participants (" + joinedCount + ")");
            }
        }

        return sessionRepository.update(sessionId, title, notes, startTime, endTime, capacity);
    }

    @Transactional
    public void cancelSession(UUID callerId, UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        if (!callerId.equals(session.hostUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the host can cancel this session");
        }

        if (!session.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Session is not active");
        }

        sessionRepository.cancel(sessionId);

        List<String> emails = participantRepository.findRegisteredParticipantEmails(sessionId);
        User host = userRepository.findById(callerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Authenticated user not found"));
        emails.stream()
            .filter(email -> !email.equals(host.email()))
            .forEach(email -> emailService.sendCancellationNotification(
                email, session.title(), session.startTime(), session.locationName()));
    }
}
