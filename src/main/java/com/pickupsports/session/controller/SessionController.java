package com.pickupsports.session.controller;

import com.pickupsports.auth.service.JwtService;
import com.pickupsports.auth.service.RateLimiterService;
import com.pickupsports.session.domain.Participant;
import com.pickupsports.session.domain.Session;
import com.pickupsports.session.domain.SessionSummary;
import com.pickupsports.session.repository.ParticipantRepository;
import com.pickupsports.session.repository.SessionRepository;
import com.pickupsports.session.service.ParticipantService;
import com.pickupsports.session.service.SessionService;
import com.pickupsports.user.domain.User;
import com.pickupsports.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final SessionRepository sessionRepository;
    private final ParticipantRepository participantRepository;
    private final ParticipantService participantService;
    private final SessionService sessionService;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final RateLimiterService rateLimiterService;

    public SessionController(SessionRepository sessionRepository,
                             ParticipantRepository participantRepository,
                             ParticipantService participantService,
                             SessionService sessionService,
                             UserRepository userRepository,
                             JwtService jwtService,
                             RateLimiterService rateLimiterService) {
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
        this.participantService = participantService;
        this.sessionService = sessionService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.rateLimiterService = rateLimiterService;
    }

    // ── Request / Response records ──────────────────────────────────────────

    record CreateSessionRequest(
        @NotBlank String sport,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String notes,
        @NotNull Double lat,
        @NotNull Double lng,
        @NotBlank String locationName,
        @NotNull Instant startTime,
        @NotNull Instant endTime,
        @Min(1) int capacity
    ) {}

    record UpdateSessionRequest(
        @Size(max = 200) String title,
        @Size(max = 2000) String notes,
        Instant startTime,
        Instant endTime,
        @Min(1) Integer capacity,
        String sport,
        @Size(max = 200) String locationName,
        Double lat,
        Double lng
    ) {}

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

    record JoinResponse(String status) {}

    record GuestJoinResponse(String status, String guestToken) {}

    record SessionSummaryResponse(
        String id, String sport, String title, String locationName,
        LocationResponse location, String startTime, String endTime,
        int capacity, int participantCount, int spotsLeft, String status
    ) {}

    record SessionListResponse(
        List<SessionSummaryResponse> content,
        int page, int size, long total
    ) {}

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<SessionListResponse> getSessions(
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lng,
            @RequestParam(defaultValue = "5000") double radius,
            @RequestParam(required = false) String sport,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        if (lat == null || lng == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "lat and lng are required");
        }
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }
        if (radius <= 0 || radius > 100_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "radius must be between 1 and 100000 meters");
        }

        Instant fromInstant;
        Instant toInstant;
        try {
            fromInstant = (from != null) ? Instant.parse(from) : Instant.now();
            toInstant = (to != null) ? Instant.parse(to) : null;
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid datetime format — use ISO 8601 (e.g. 2026-03-10T19:00:00Z)");
        }

        List<SessionSummary> sessions = sessionRepository.findNearby(
            lat, lng, radius, sport, fromInstant, toInstant, page, size);
        long total = sessionRepository.countNearby(lat, lng, radius, sport, fromInstant, toInstant);

        List<SessionSummaryResponse> content = sessions.stream()
            .map(s -> new SessionSummaryResponse(
                s.id().toString(), s.sport(), s.title(), s.locationName(),
                new LocationResponse(s.lat(), s.lng()),
                s.startTime().toString(), s.endTime().toString(),
                s.capacity(), s.participantCount(), s.spotsLeft(), s.status()
            ))
            .toList();

        return ResponseEntity.ok(new SessionListResponse(content, page, size, total));
    }

    @PostMapping
    public ResponseEntity<SessionDetailResponse> createSession(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody CreateSessionRequest request) {

        UUID hostUserId = requireAuth(authHeader);

        if (!request.startTime().isBefore(request.endTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }

        Session session = sessionService.createSession(
            hostUserId,
            request.sport(), request.title(), request.notes(),
            request.startTime(), request.endTime(), request.capacity(),
            request.lat(), request.lng(), request.locationName()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(buildDetailResponse(session.id()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SessionDetailResponse> updateSession(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody UpdateSessionRequest request) {

        UUID callerId = requireAuth(authHeader);
        sessionService.updateSession(callerId, id,
            request.title(), request.notes(),
            request.startTime(), request.endTime(), request.capacity(),
            request.sport(), request.locationName(), request.lat(), request.lng());

        return ResponseEntity.ok(buildDetailResponse(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelSession(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        UUID callerId = requireAuth(authHeader);
        sessionService.cancelSession(callerId, id);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<SessionDetailResponse> getSession(@PathVariable UUID id) {
        return ResponseEntity.ok(buildDetailResponse(id));
    }

    @PostMapping("/{id}/join")
    public ResponseEntity<JoinResponse> joinSession(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        UUID userId = requireAuth(authHeader);
        var result = participantService.join(id, userId);
        return ResponseEntity.ok(new JoinResponse(result.status()));
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

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID requireAuth(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return jwtService.parseUserId(authHeader.substring(7));
    }

    private SessionDetailResponse buildDetailResponse(UUID sessionId) {
        Session session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found"));

        User host = userRepository.findById(session.hostUserId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Host user not found"));

        List<Participant> participants = participantRepository.findBySessionId(sessionId);
        int joinedCount = (int) participants.stream()
            .filter(p -> "joined".equals(p.status()))
            .count();
        int spotsLeft = Math.max(0, session.capacity() - joinedCount);

        List<ParticipantResponse> participantResponses = participants.stream()
            .map(p -> new ParticipantResponse(
                p.isGuest() ? null : p.userId().toString(),
                p.displayName(),
                p.status(),
                p.isGuest()
            ))
            .toList();

        return new SessionDetailResponse(
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
        );
    }
}
