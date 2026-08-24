package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.generated.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.OffsetDateTime;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders trial-expiry denials as the same {@link ErrorResponse} shape as every other API error,
 * instead of Spring Security's default empty 403 body. See TrialAuthorizationManager — this app
 * has no other authorization-denial reason today, so every AccessDeniedException is treated as a
 * trial expiry.
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
        ErrorResponse body = new ErrorResponse("TRIAL_EXPIRED", "Your trial period has ended. Contact sales to continue.")
                .timestamp(OffsetDateTime.now())
                .path(request.getRequestURI());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
