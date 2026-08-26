package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.generated.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders access denials as the same {@link ErrorResponse} shape as every other API error,
 * instead of Spring Security's default empty 403 body. Distinguishes a trial-expiry denial
 * (see TrialAuthorizationManager) from any other denial reason (e.g. a non-admin hitting an
 * admin-only {@code @PreAuthorize} check) so the latter doesn't get mislabeled TRIAL_EXPIRED.
 */
@Component
public class TrialExpiredAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public TrialExpiredAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws java.io.IOException {
        ErrorResponse body = isTrialExpired()
                ? new ErrorResponse("TRIAL_EXPIRED", "Your trial period has ended. Contact sales to continue.")
                : new ErrorResponse("ACCESS_DENIED", "You do not have permission to perform this action.");
        body.timestamp(OffsetDateTime.now()).path(request.getRequestURI());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    private boolean isTrialExpired() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return false;
        }
        Instant trialEndDate = jwtAuth.getToken().getClaimAsInstant("trialEndDate");
        return trialEndDate != null && Instant.now().isAfter(trialEndDate);
    }
}
