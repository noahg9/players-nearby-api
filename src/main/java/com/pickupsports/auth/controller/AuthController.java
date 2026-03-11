package com.pickupsports.auth.controller;

import com.pickupsports.auth.service.AuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    record RequestLoginRequest(@NotBlank @Email String email) {}
    record ConfirmRequest(@NotBlank String token) {}
    record UserResponse(String id, String email, String name) {}
    record ConfirmResponse(String token, UserResponse user) {}

    @PostMapping("/request-login")
    public ResponseEntity<Void> requestLogin(@Valid @RequestBody RequestLoginRequest request) {
        authService.requestLogin(request.email());
        return ResponseEntity.ok().build();
    }

    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponse> confirm(@Valid @RequestBody ConfirmRequest request) {
        var result = authService.confirm(request.token());
        var userResponse = new UserResponse(
            result.user().id().toString(), result.user().email(), result.user().name());
        return ResponseEntity.ok(new ConfirmResponse(result.jwt(), userResponse));
    }
}
