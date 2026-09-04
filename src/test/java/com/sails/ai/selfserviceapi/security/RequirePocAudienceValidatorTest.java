package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class RequirePocAudienceValidatorTest {

    private final RequirePocAudienceValidator validator = new RequirePocAudienceValidator();

    @Test
    void acceptsAPocScopedToken() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt(Map.of("sub", "user-1", "aud", List.of("poc:contract-agent"))));

        assertThat(result.hasErrors()).isFalse();
    }

    /** The case that matters: a portal token must not authenticate on the POC-files chain. */
    @Test
    void rejectsAUserAccessTokenCarryingNoPocAudience() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(Map.of("sub", "user-1")));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getDescription()).contains("POC-scoped token is required"));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}
