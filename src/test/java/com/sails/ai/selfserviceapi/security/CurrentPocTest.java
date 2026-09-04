package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentPocTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsThePocIdClaim() {
        authenticateAs(jwt(Map.of("sub", "user-1", "pocId", 4L)));

        assertThat(CurrentPoc.id()).isEqualTo(4L);
    }

    /**
     * JSON has one number type; a small pocId can come back deserialized as an Integer rather
     * than a Long depending on the decoder. Both must resolve to the same Long id.
     */
    @Test
    void acceptsAnIntegerClaimTheSameAsALong() {
        authenticateAs(jwt(Map.of("sub", "user-1", "pocId", 4)));

        assertThat(CurrentPoc.id()).isEqualTo(4L);
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
