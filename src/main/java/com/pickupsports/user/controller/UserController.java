package com.pickupsports.user.controller;

import com.pickupsports.auth.service.JwtService;
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

    public UserController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
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
