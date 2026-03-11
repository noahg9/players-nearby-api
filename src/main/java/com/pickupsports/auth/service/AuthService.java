package com.pickupsports.auth.service;

import com.pickupsports.auth.domain.AuthToken;
import com.pickupsports.auth.repository.AuthTokenRepository;
import com.pickupsports.user.domain.User;
import com.pickupsports.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class AuthService {

    private final AuthTokenRepository authTokenRepository;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final EmailService emailService;
    private final RateLimiterService rateLimiterService;

    public AuthService(AuthTokenRepository authTokenRepository, UserRepository userRepository,
                       JwtService jwtService, EmailService emailService,
                       RateLimiterService rateLimiterService) {
        this.authTokenRepository = authTokenRepository;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
        this.emailService = emailService;
        this.rateLimiterService = rateLimiterService;
    }

    public void requestLogin(String email) {
        // Normalize email to prevent duplicate accounts from case variants (e.g. User@Example.com vs user@example.com)
        String normalizedEmail = email.toLowerCase();
        if (!rateLimiterService.tryConsumeRequestLogin(normalizedEmail)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Too many magic link requests. Try again in an hour.");
        }
        String token = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);
        authTokenRepository.save(new AuthToken(UUID.randomUUID(), token, normalizedEmail, expiresAt, null, null));
        emailService.sendMagicLink(normalizedEmail, token);
    }

    public record ConfirmResult(String jwt, User user) {}

    @Transactional
    public ConfirmResult confirm(String token) {
        AuthToken authToken = authTokenRepository.findByToken(token)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid token"));

        if (Instant.now().isAfter(authToken.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token expired");
        }

        // Atomically mark as used — guards against TOCTOU races where two concurrent
        // requests consume the same token and both receive a valid JWT.
        boolean marked = authTokenRepository.markUsedIfUnused(token, Instant.now());
        if (!marked) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token already used");
        }

        User user = findOrCreateUser(authToken.email());
        return new ConfirmResult(jwtService.generateToken(user.id(), user.email()), user);
    }

    private User findOrCreateUser(String email) {
        try {
            return userRepository.findByEmail(email)
                .orElseGet(() -> {
                    String name = email.split("@")[0];
                    return userRepository.save(
                        new User(UUID.randomUUID(), email, name, null, Instant.now()));
                });
        } catch (DataIntegrityViolationException e) {
            // Two concurrent confirms for the same email both saw no user and both attempted
            // INSERT — the second one hits the unique email constraint. Re-fetch the winner's row.
            return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to resolve user account"));
        }
    }
}
