package com.sails.ai.selfserviceapi.security;

import java.time.Instant;
import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Denies access once the authenticated user's trial has ended. Tokens with no
 * {@code trialEndDate} claim (no trial concept for that user) or a non-JWT authentication are
 * allowed through unconditionally — this manager only ever votes "deny," never "authenticate."
 */
@Component
public class TrialAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationDecision authorize(Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        Authentication auth = authentication.get();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth)) {
            return new AuthorizationDecision(true);
        }

        Jwt jwt = jwtAuth.getToken();
        Instant trialEndDate = jwt.getClaimAsInstant("trialEndDate");
        if (trialEndDate == null) {
            return new AuthorizationDecision(true);
        }

        return new AuthorizationDecision(Instant.now().isBefore(trialEndDate));
    }
}
