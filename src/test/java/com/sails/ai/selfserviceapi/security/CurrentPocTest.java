package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentPocTest {

    private static final UUID POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsThePocIdClaim() {
        authenticateAs(jwt(Map.of("sub", "user-1", "pocId", POC_ID.toString())));

        assertThat(CurrentPoc.id()).isEqualTo(POC_ID);
    }

    @Test
    void refusesAMalformedPocIdClaim() {
        authenticateAs(jwt(Map.of("sub", "user-1", "pocId", "not-a-uuid")));

        assertThatThrownBy(CurrentPoc::id).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesATokenWithNoPocIdClaim() {
        authenticateAs(jwt(Map.of("sub", "user-1")));

        assertThatThrownBy(CurrentPoc::id).isInstanceOf(IllegalStateException.class);
    }

    private static void authenticateAs(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}
