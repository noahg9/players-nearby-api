package com.pickupsports.chat.controller;

import com.pickupsports.auth.service.JwtService;
import com.pickupsports.chat.domain.ChatMessage;
import com.pickupsports.chat.service.ChatService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sessions/{id}/messages")
public class ChatController {

    private final ChatService chatService;
    private final JwtService jwtService;

    public ChatController(ChatService chatService, JwtService jwtService) {
        this.chatService = chatService;
        this.jwtService = jwtService;
    }

    // ── Request / Response records ──────────────────────────────────────────

    record PostMessageRequest(
        @NotBlank @Size(max = 500) String content,
        String guestToken  // optional — only for guests
    ) {}

    record MessageResponse(UUID id, String authorName, String content, Instant sentAt) {}

    record MessagesResponse(List<MessageResponse> content, int total, int page, int size) {}

    // ── Endpoints ───────────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<MessagesResponse> getMessages(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be >= 0");
        }
        if (size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100");
        }

        ChatService.MessagesPage result = chatService.getMessages(id, page, size);
        List<MessageResponse> content = result.content().stream()
            .map(m -> new MessageResponse(m.id(), m.authorName(), m.content(), m.sentAt()))
            .toList();

        return ResponseEntity.ok(new MessagesResponse(content, result.total(), result.page(), result.size()));
    }

    @PostMapping
    public ResponseEntity<MessageResponse> postMessage(
            @PathVariable UUID id,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @Valid @RequestBody PostMessageRequest body) {

        UUID userId = resolveUserId(authHeader);
        String guestToken = body.guestToken();

        if (userId == null && (guestToken == null || guestToken.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }

        ChatMessage msg = chatService.postMessage(id, userId, guestToken, body.content());
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new MessageResponse(msg.id(), msg.authorName(), msg.content(), msg.sentAt()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private UUID resolveUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return null;
        try {
            return jwtService.parseUserId(authHeader.substring(7));
        } catch (Exception e) {
            return null;  // invalid token treated as unauthenticated
        }
    }
}
