package com.pickupsports.auth.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String FROM_ADDRESS = "onboarding@resend.dev";

    private final String appBaseUrl;
    private final Resend resend;
    private final boolean emailEnabled;

    public EmailService(
        @Value("${app.resend-api-key}") String resendApiKey,
        @Value("${app.base-url}") String appBaseUrl
    ) {
        this.appBaseUrl = appBaseUrl;
        this.emailEnabled = resendApiKey != null && !resendApiKey.isBlank();
        this.resend = emailEnabled ? new Resend(resendApiKey) : null;
    }

    public void sendMagicLink(String toEmail, String token) {
        // Strip trailing slash to guard against double-slash if APP_BASE_URL is misconfigured with a trailing slash
        String magicLinkUrl = appBaseUrl.stripTrailing() + "/auth/confirm?token=" + token;

        if (!emailEnabled) {
            // Log at DEBUG only — WARN-level logs often ship to monitoring systems where a
            // live magic link token would be exploitable by anyone with log access.
            log.debug("Resend API key not configured — magic link not sent to {}", toEmail);
            return;
        }

        try {
            CreateEmailOptions params = CreateEmailOptions.builder()
                .from(FROM_ADDRESS)
                .to(toEmail)
                .subject("Your Pickup Sports login link")
                .html("<p>Click <a href=\"" + magicLinkUrl + "\">here</a> to log in to Pickup Sports.</p>"
                    + "<p>This link expires in 15 minutes. If you did not request this, ignore this email.</p>")
                .build();
            resend.emails().send(params);
        } catch (ResendException e) {
            log.error("Failed to send magic link to {}: {}", toEmail, e.getMessage());
            // Do NOT re-throw — caller returns 200 OK regardless.
            // Prevents email enumeration attacks and avoids user-visible errors for transient email failures.
        }
    }

    public void sendCancellationNotification(String toEmail, String sessionTitle,
                                             Instant startTime, String locationName) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — cancellation notification not sent to {}", toEmail);
            return;
        }

        String formattedTime = DateTimeFormatter.ofPattern("EEE, MMM d yyyy 'at' HH:mm 'UTC'")
            .withZone(ZoneOffset.UTC)
            .format(startTime);

        try {
            CreateEmailOptions params = CreateEmailOptions.builder()
                .from(FROM_ADDRESS)
                .to(toEmail)
                .subject("Session cancelled: " + escapeHtml(sessionTitle))
                .html("<p>The following session has been cancelled by the host:</p>"
                    + "<p><strong>" + escapeHtml(sessionTitle) + "</strong><br>"
                    + escapeHtml(locationName) + "<br>"
                    + formattedTime + "</p>"
                    + "<p>We hope to see you at another session soon!</p>")
                .build();
            resend.emails().send(params);
        } catch (ResendException e) {
            log.error("Failed to send cancellation notification to {}: {}", toEmail, e.getMessage());
            // Do NOT re-throw — cancellation should succeed even if email delivery fails
        }
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;");
    }
}
