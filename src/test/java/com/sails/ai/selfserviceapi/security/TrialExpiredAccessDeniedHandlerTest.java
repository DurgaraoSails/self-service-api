package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class TrialExpiredAccessDeniedHandlerTest {

    private final TrialExpiredAccessDeniedHandler handler = new TrialExpiredAccessDeniedHandler(new ObjectMapper());

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void reportsAccessDeniedWhenTrialHasNotExpired() throws Exception {
        long future = Instant.now().plus(1, ChronoUnit.DAYS).getEpochSecond();
        authenticateWithJwt(Map.of("sub", "user-1", "trialEndDate", future));

        MockHttpServletResponse response = handle();

        assertThat(response.getContentAsString()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    @Test
    void reportsAccessDeniedWhenNoTrialClaimIsPresent() throws Exception {
        authenticateWithJwt(Map.of("sub", "user-1"));

        MockHttpServletResponse response = handle();

        assertThat(response.getContentAsString()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    @Test
    void reportsTrialExpiredWhenTrialEndDateHasPassed() throws Exception {
        long past = Instant.now().minus(1, ChronoUnit.DAYS).getEpochSecond();
        authenticateWithJwt(Map.of("sub", "user-1", "trialEndDate", past));

        MockHttpServletResponse response = handle();

        assertThat(response.getContentAsString()).contains("\"code\":\"TRIAL_EXPIRED\"");
    }

    @Test
    void reportsAccessDeniedForNonJwtAuthentication() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("user", "creds"));

        MockHttpServletResponse response = handle();

        assertThat(response.getContentAsString()).contains("\"code\":\"ACCESS_DENIED\"");
    }

    private MockHttpServletResponse handle() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("denied"));
        return response;
    }

    private void authenticateWithJwt(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
