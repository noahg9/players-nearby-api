package com.pickupsports.auth.service;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final String FROM_ADDRESS = "noreply@playersnearby.com";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
        .ofPattern("EEE, MMM d yyyy 'at' HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);

    private final String appBaseUrl;
    private final Resend resend;
    private final boolean emailEnabled;
    private final TemplateEngine templateEngine;

    public EmailService(
        @Value("${app.resend-api-key}") String resendApiKey,
        @Value("${app.base-url}") String appBaseUrl,
        TemplateEngine templateEngine
    ) {
        this.appBaseUrl = appBaseUrl;
        this.emailEnabled = resendApiKey != null && !resendApiKey.isBlank();
        this.resend = emailEnabled ? new Resend(resendApiKey) : null;
        this.templateEngine = templateEngine;
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

        Context ctx = baseContext();
        ctx.setVariable("magicLinkUrl", magicLinkUrl);

        send(toEmail, "Your login link for Players Nearby", "email/magic-link", ctx,
            "Failed to send magic link to {}");
    }

    public void sendCancellationNotification(String toEmail, String sessionTitle,
                                             Instant startTime, String locationName) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — cancellation notification not sent to {}", toEmail);
            return;
        }

        Context ctx = baseContext();
        ctx.setVariable("sessionTitle", sessionTitle);
        ctx.setVariable("locationName", locationName);
        ctx.setVariable("formattedTime", DATE_FORMATTER.format(startTime));

        send(toEmail, "Session cancelled: " + sessionTitle, "email/cancellation", ctx,
            "Failed to send cancellation notification to {}");
    }

    public void sendWaitlistPromotion(String toEmail, String sessionTitle,
                                      Instant startTime, String locationName, java.util.UUID sessionId) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — waitlist promotion not sent to {}", toEmail);
            return;
        }

        Context ctx = baseContext();
        ctx.setVariable("sessionTitle", sessionTitle);
        ctx.setVariable("locationName", locationName);
        ctx.setVariable("formattedTime", DATE_FORMATTER.format(startTime));
        ctx.setVariable("sessionUrl", appBaseUrl.stripTrailing() + "/sessions/" + sessionId);

        send(toEmail, "You're in! A spot opened for " + sessionTitle, "email/waitlist-promotion", ctx,
            "Failed to send waitlist promotion to {}");
    }

    public void sendSessionReminder(String toEmail, String sessionTitle,
                                    Instant startTime, String locationName, java.util.UUID sessionId) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — session reminder not sent to {}", toEmail);
            return;
        }

        Context ctx = baseContext();
        ctx.setVariable("sessionTitle", sessionTitle);
        ctx.setVariable("locationName", locationName);
        ctx.setVariable("formattedTime", DATE_FORMATTER.format(startTime));
        ctx.setVariable("sessionUrl", appBaseUrl.stripTrailing() + "/sessions/" + sessionId);

        send(toEmail, "Starting in 1 hour: " + sessionTitle, "email/session-reminder", ctx,
            "Failed to send session reminder to {}");
    }

    public void sendHostJoinNotification(String toEmail, String joinerName, String sessionTitle,
                                         java.util.UUID sessionId) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — host join notification not sent to {}", toEmail);
            return;
        }

        Context ctx = baseContext();
        ctx.setVariable("joinerName", joinerName);
        ctx.setVariable("sessionTitle", sessionTitle);
        ctx.setVariable("sessionUrl", appBaseUrl.stripTrailing() + "/sessions/" + sessionId);

        send(toEmail, joinerName + " joined your session", "email/host-join", ctx,
            "Failed to send host join notification to {}");
    }

    public void sendInvite(String toEmail, String sessionTitle, String sport,
                           String locationName, Instant startTime, java.util.UUID sessionId) {
        if (!emailEnabled) {
            log.debug("Resend API key not configured — invite not sent to {}", toEmail);
            return;
        }

        Context ctx = baseContext();
        ctx.setVariable("sessionTitle", sessionTitle);
        ctx.setVariable("sport", sport);
        ctx.setVariable("locationName", locationName);
        ctx.setVariable("formattedTime", DATE_FORMATTER.format(startTime));
        ctx.setVariable("sessionUrl", appBaseUrl.stripTrailing() + "/sessions/" + sessionId);

        send(toEmail, "You're invited to a " + sport + " session", "email/invite", ctx,
            "Failed to send invite to {}");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Returns a Context pre-loaded with variables available in every template. */
    private Context baseContext() {
        Context ctx = new Context();
        ctx.setVariable("appBaseUrl", appBaseUrl.stripTrailing());
        return ctx;
    }

    private void send(String toEmail, String subject, String template, Context ctx, String errorMessage) {
        try {
            String html = templateEngine.process(template, ctx);
            CreateEmailOptions params = CreateEmailOptions.builder()
                .from(FROM_ADDRESS)
                .to(toEmail)
                .subject(subject)
                .html(html)
                .build();
            resend.emails().send(params);
        } catch (ResendException e) {
            log.error(errorMessage + ": {}", toEmail, e.getMessage());
            // Do NOT re-throw — email failures should not surface to callers.
        }
    }
}
