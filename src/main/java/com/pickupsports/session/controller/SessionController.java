package com.pickupsports.session.controller;

import com.pickupsports.auth.service.JwtService;
import com.pickupsports.auth.service.RateLimiterService;
import com.pickupsports.session.domain.Participant;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.session.service.ParticipantService;
import com.pickupsports.user.domain.User;
import com.pickupsports.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantService participantService;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;

    public SessionController(SessionRepository sessionRepository,
                             ParticipantRepository participantRepository,
                             ParticipantService participantService,
                             UserRepository userRepository,
                             JwtService jwtService,
                             RateLimiterService rateLimiterService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.participantService = participantService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.rateLimiterService = rateLimiterService;
    }

    // ── Request / Response records ──────────────────────────────────────────

    record GuestJoinRequest(@NotBlank @Size(min = 1, max = 50) String name) {}

    record LeaveRequest(String guestToken) {}

    record LocationResponse(double lat, double lng) {}

    record HostResponse(String id, String name) {}

    record ParticipantResponse(String id, String name, String status, boolean isGuest) {}

    record SessionDetailResponse(
        String id, String sport, String title, String notes,
        String locationName, LocationResponse location,
        String startTime, String endTime,
        int capacity, int spotsLeft, String status,
        HostResponse host,
        List<ParticipantResponse> participants
    ) {}

    record GuestJoinResponse(String status, String guestToken) {}

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    public ResponseEntity<SessionDetailResponse> getSession(@PathVariable UUID id) {
        Session session = sessionRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        User host = userRepository.findById(session.hostUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Host user not found"));

        List<Participant> participants = participantRepository.findBySessionId(id);
        int joinedCount = (int) participants.stream()
            .filter(p -> "joined".equals(p.status()))
            .count();
        int spotsLeft = Math.max(0, session.capacity() - joinedCount);

        List<ParticipantResponse> participantResponses = participants.stream()
            .map(p -> new ParticipantResponse(
                p.isGuest() ? null : p.id().toString(),
                p.displayName(),
                p.status(),
                p.isGuest()
            ))
            .toList();

        return ResponseEntity.ok(new SessionDetailResponse(
            session.id().toString(),
            session.sport(),
            session.title(),
            session.notes(),
            session.locationName(),
            new LocationResponse(session.lat(), session.lng()),
            session.startTime().toString(),
            session.endTime().toString(),
            session.capacity(),
            spotsLeft,
            session.status(),
            new HostResponse(host.id().toString(), host.name()),
            participantResponses
        ));
    }

    @PostMapping("/{id}/guest-join")
    public ResponseEntity<GuestJoinResponse> guestJoin(
            @PathVariable UUID id,
            @Valid @RequestBody GuestJoinRequest request,
            HttpServletRequest httpRequest) {

        String ip = httpRequest.getRemoteAddr();
        if (!rateLimiterService.tryConsumeGuestJoin(ip)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many guest join attempts. Try again later.");
        }

        var result = participantService.guestJoin(id, request.name());
        return ResponseEntity.ok(new GuestJoinResponse(result.status(), result.guestToken()));
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<Void> leaveSession(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) LeaveRequest body) {

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            UUID userId = jwtService.parseUserId(authHeader.substring(7));
            participantService.leave(id, userId);
        } else if (body != null && body.guestToken() != null) {
            participantService.guestLeave(id, body.guestToken());
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Provide an Authorization header or a guestToken in the request body");
        }

        return ResponseEntity.ok().build();
    }
}
