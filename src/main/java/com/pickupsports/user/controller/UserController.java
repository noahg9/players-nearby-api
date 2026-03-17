package com.pickupsports.user.controller;

import com.pickupsports.auth.service.JwtService;
import com.pickupsports.session.domain.SessionSummary;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.user.domain.UserSportProfile;
import com.pickupsports.user.service.UserService;
import com.pickupsports.user.service.UserService.UserWithSports;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;
    private final JwtService jwtService;
    private final SessionRepository sessionRepository;

    public UserController(UserService userService, JwtService jwtService, SessionRepository sessionRepository) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.sessionRepository = sessionRepository;
    }

    // ── Request / Response records ───────────────────────────────────────────

    record UpdateMeRequest(
        @Size(min = 1, max = 100) String name,
        @Size(max = 500) String bio
    ) {}

    record UpsertSportRequest(@NotBlank String skillLevel) {}

    record SportProfileResponse(String sport, String skillLevel) {}

    record UserMeResponse(String id, String email, String name, String bio,
                          List<SportProfileResponse> sportProfiles) {}

    record PublicUserResponse(String id, String name, String bio,
                              List<SportProfileResponse> sportProfiles) {}

    record LocationResponse(double lat, double lng) {}

    record MySessionSummaryResponse(
        String id, String sport, String title, String locationName,
        LocationResponse location, String startTime, String endTime,
        int capacity, int participantCount, int spotsLeft, String status
    ) {}

    record MySessionsResponse(
        List<MySessionSummaryResponse> content,
        int page, int size, long total
    ) {}

    // ── Endpoints ────────────────────────────────────────────────────────────

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMe(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = requireAuth(authHeader);
        UserWithSports result = userService.getMe(userId);
        return ResponseEntity.ok(toMeResponse(result));
    }

    @PutMapping("/me")
    public ResponseEntity<UserMeResponse> updateMe(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody UpdateMeRequest request) {
        UUID userId = requireAuth(authHeader);
        UserWithSports result = userService.updateMe(userId, request.name(), request.bio());
        return ResponseEntity.ok(toMeResponse(result));
    }

    @PutMapping("/me/sports/{sport}")
    public ResponseEntity<SportProfileResponse> upsertSport(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @PathVariable String sport,
            @Valid @RequestBody UpsertSportRequest request) {
        UUID userId = requireAuth(authHeader);
        UserSportProfile profile = userService.upsertSportProfile(userId, sport, request.skillLevel());
        return ResponseEntity.ok(new SportProfileResponse(profile.sport(), profile.skillLevel()));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UUID userId = requireAuth(authHeader);
        userService.deleteAccount(userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me/sessions")
    public ResponseEntity<MySessionsResponse> getMySessions(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestParam(defaultValue = "all") String role,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        UUID userId = requireAuth(authHeader);

        if (!List.of("all", "hosting", "joined").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "role must be 'all', 'hosting', or 'joined'");
        }
        if (!List.of("all", "active", "completed", "cancelled").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "status must be 'all', 'active', 'completed', or 'cancelled'");
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }

        List<SessionSummary> sessions = sessionRepository.findByUserId(userId, role, status, page, size);
        long total = sessionRepository.countByUserId(userId, role, status);

        List<MySessionSummaryResponse> content = sessions.stream()
            .map(s -> new MySessionSummaryResponse(
                s.id().toString(), s.sport(), s.title(), s.locationName(),
                new LocationResponse(s.lat(), s.lng()),
                s.startTime().toString(), s.endTime().toString(),
                s.capacity(), s.participantCount(), s.spotsLeft(), s.status()
            ))
            .toList();

        return ResponseEntity.ok(new MySessionsResponse(content, page, size, total));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PublicUserResponse> getPublicProfile(@PathVariable UUID id) {
        UserWithSports result = userService.getPublicProfile(id);
        return ResponseEntity.ok(toPublicResponse(result));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID requireAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return jwtService.parseUserId(authHeader.substring(7));
    }

    private UserMeResponse toMeResponse(UserWithSports result) {
        List<SportProfileResponse> sports = result.sports().stream()
            .map(s -> new SportProfileResponse(s.sport(), s.skillLevel()))
            .toList();
        return new UserMeResponse(
            result.user().id().toString(),
            result.user().email(),
            result.user().name(),
            result.user().bio(),
            sports
        );
    }

    private PublicUserResponse toPublicResponse(UserWithSports result) {
        List<SportProfileResponse> sports = result.sports().stream()
            .map(s -> new SportProfileResponse(s.sport(), s.skillLevel()))
            .toList();
        return new PublicUserResponse(
            result.user().id().toString(),
            result.user().name(),
            result.user().bio(),
            sports
        );
    }
}
