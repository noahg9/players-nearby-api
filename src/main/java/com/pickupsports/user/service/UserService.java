package com.pickupsports.user.service;

import com.pickupsports.auth.service.EmailService;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.user.domain.User;
import com.pickupsports.user.domain.UserSportProfile;
import com.pickupsports.user.repository.UserRepository;
import com.pickupsports.user.repository.UserSportProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final List<String> VALID_SKILL_LEVELS = List.of("beginner", "intermediate", "advanced");

    private final UserRepository userRepository;
    private final UserSportProfileRepository sportProfileRepository;
    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final EmailService emailService;

    public UserService(UserRepository userRepository,
                       UserSportProfileRepository sportProfileRepository,
                       SessionRepository sessionRepository,
                       ParticipantRepository participantRepository,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.sportProfileRepository = sportProfileRepository;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.emailService = emailService;
    }

    public UserWithSports getMe(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        List<UserSportProfile> sports = sportProfileRepository.findByUserId(userId);
        return new UserWithSports(user, sports);
    }

    public UserWithSports updateMe(UUID userId, String name, String bio) {
        User updated = userRepository.update(userId, name, bio);
        List<UserSportProfile> sports = sportProfileRepository.findByUserId(userId);
        return new UserWithSports(updated, sports);
    }

    public UserSportProfile upsertSportProfile(UUID userId, String sport, String skillLevel) {
        if (!VALID_SKILL_LEVELS.contains(skillLevel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "skillLevel must be one of: beginner, intermediate, advanced");
        }
        userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return sportProfileRepository.upsert(userId, sport, skillLevel);
    }

    public UserWithSports getPublicProfile(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        List<UserSportProfile> sports = sportProfileRepository.findByUserId(userId);
        return new UserWithSports(user, sports);
    }

    @Transactional
    public void deleteAccount(UUID userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        // Cancel all active hosted sessions and notify registered participants
        List<Session> activeSessions = sessionRepository.findActiveSessionsByHostUserId(userId);
        for (Session session : activeSessions) {
            sessionRepository.cancel(session.id());
            List<String> emails = participantRepository.findRegisteredParticipantEmails(session.id());
            emails.stream()
                .filter(email -> !email.equals(user.email()))
                .forEach(email -> emailService.sendCancellationNotification(
                    email, session.title(), session.startTime(), session.locationName()));
        }

        // Hard delete — CASCADE handles user_sport_profiles and session_participants (user_id FK)
        // ON DELETE SET NULL (V6 migration) handles sessions.host_user_id
        userRepository.deleteById(userId);
    }

    public record UserWithSports(User user, List<UserSportProfile> sports) {}
}
