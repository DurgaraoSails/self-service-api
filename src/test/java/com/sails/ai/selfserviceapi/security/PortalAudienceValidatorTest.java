package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class PortalAudienceValidatorTest {

    private final PortalAudienceValidator validator = new PortalAudienceValidator();

    @Test
    void acceptsAUserAccessToken() {
        OAuth2TokenValidatorResult result = validator.validate(jwt(Map.of("sub", "user-1")));

        assertThat(result.hasErrors()).isFalse();
    }

    /**
     * The point of the whole class: this token is resident in JavaScript on a POC's own origin,
     * and would otherwise satisfy anyRequest().authenticated() against /users/me and every
     * admin endpoint.
     */
    @Test
    void rejectsAPocScopedToken() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt(Map.of("sub", "user-1", "aud", List.of("poc:contract-agent"))));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anySatisfy(error ->
                assertThat(error.getDescription()).contains("POC-scoped"));
    }

    @Test
    void rejectsATokenCarryingAPocAudienceAlongsideOthers() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt(Map.of("sub", "user-1", "aud", List.of("self-service-portal", "poc:contract-agent"))));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void acceptsAnAudienceThatMerelyContainsPoc() {
        OAuth2TokenValidatorResult result = validator.validate(
                jwt(Map.of("sub", "user-1", "aud", List.of("something-poc:like"))));

        assertThat(result.hasErrors()).isFalse();
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}
