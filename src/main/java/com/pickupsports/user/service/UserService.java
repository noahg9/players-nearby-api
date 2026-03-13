package com.pickupsports.user.service;

import com.pickupsports.user.domain.User;
import com.pickupsports.user.domain.UserSportProfile;
import com.pickupsports.user.repository.UserRepository;
import com.pickupsports.user.repository.UserSportProfileRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final List<String> VALID_SKILL_LEVELS = List.of("beginner", "intermediate", "advanced");

    private final UserRepository userRepository;
    private final UserSportProfileRepository sportProfileRepository;

    public UserService(UserRepository userRepository, UserSportProfileRepository sportProfileRepository) {
        this.userRepository = userRepository;
        this.sportProfileRepository = sportProfileRepository;
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

    public record UserWithSports(User user, List<UserSportProfile> sports) {}
}
