package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

class TrialAuthorizationManagerTest {

    private final TrialAuthorizationManager manager = new TrialAuthorizationManager();

    @Test
    void allowsWhenNoTrialClaimPresent() {
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-1"));
        AuthorizationDecision decision = manager.authorize(() -> new JwtAuthenticationToken(jwt), context());
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void allowsWhenTrialEndDateIsInTheFuture() {
        long future = Instant.now().plus(1, ChronoUnit.DAYS).getEpochSecond();
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-1", "trialEndDate", future));
        AuthorizationDecision decision = manager.authorize(() -> new JwtAuthenticationToken(jwt), context());
        assertThat(decision.isGranted()).isTrue();
    }

    @Test
    void deniesWhenTrialEndDateIsInThePast() {
        long past = Instant.now().minus(1, ChronoUnit.DAYS).getEpochSecond();
        Jwt jwt = jwtWithClaims(Map.of("sub", "user-1", "trialEndDate", past));
        AuthorizationDecision decision = manager.authorize(() -> new JwtAuthenticationToken(jwt), context());
        assertThat(decision.isGranted()).isFalse();
    }

    @Test
    void allowsNonJwtAuthentication() {
        Authentication auth = new UsernamePasswordAuthenticationToken("user", "creds");
        AuthorizationDecision decision = manager.authorize(() -> auth, context());
        assertThat(decision.isGranted()).isTrue();
    }

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
    }

    private RequestAuthorizationContext context() {
        return new RequestAuthorizationContext(new org.springframework.mock.web.MockHttpServletRequest());
    }
}
