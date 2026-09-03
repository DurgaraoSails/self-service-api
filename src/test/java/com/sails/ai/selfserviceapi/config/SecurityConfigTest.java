package com.sails.ai.selfserviceapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.security.JwtKeySet;
import com.sails.ai.selfserviceapi.security.JwksController;
import com.sails.ai.selfserviceapi.security.JwtProperties;
import com.sails.ai.selfserviceapi.security.PocAudience;
import com.sails.ai.selfserviceapi.security.TrialAuthorizationManager;
import com.sails.ai.selfserviceapi.security.TrialExpiredAccessDeniedHandler;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The wiring nothing else covers. Both classes of token are signed by the same key and carry the
 * same issuer, so what separates them is configuration in one file — and a mistake there is
 * invisible until a POC-scoped token reaches something it should never have reached.
 *
 * <p>Only {@link JwksController} is registered, which is what makes the last two cases legible: a
 * request that authenticates reaches a route that does not exist and gets 404, while one that fails
 * to authenticate is stopped at the filter and gets 401. The difference between those two codes is
 * the assertion.
 */
@WebMvcTest(controllers = JwksController.class)
@Import({SecurityConfig.class, CorsConfig.class, SecurityConfigTest.TestKeys.class})
class SecurityConfigTest {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void jwksIsReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"))
                .andExpect(jsonPath("$.keys[0].kid").isNotEmpty())
                .andExpect(jsonPath("$.keys[0].n").isNotEmpty());
    }

    /** A public key set is useless if a POC has to authenticate to fetch it. */
    @Test
    void jwksPublishesNoPrivateMaterial() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }

    @Test
    void rejectsARequestWithNoToken() throws Exception {
        mockMvc.perform(get("/users/me"))
                .andExpect(status().isUnauthorized());
    }

    /** Authenticates, then 404s on a route this slice does not register. 404 means it got through. */
    @Test
    void acceptsAUserAccessToken() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isNotFound());
    }

    /**
     * The one that matters. Same key, same issuer, still unusable here — otherwise a token handed
     * to JavaScript on a POC's origin would read the portal user's own profile.
     */
    @Test
    void rejectsAPocScopedTokenOnAPortalEndpoint() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + pocToken()))
                .andExpect(status().isUnauthorized());
    }

    private static String userToken() {
        return baseToken().compact();
    }

    private static String pocToken() {
        return baseToken().audience().add(PocAudience.forSlug("contract-agent")).and().compact();
    }

    private static io.jsonwebtoken.JwtBuilder baseToken() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("self-service-api")
                .subject("user-1")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(300)))
                .signWith((RSAPrivateKey) KEY_PAIR.getPrivate(), Jwts.SIG.RS256);
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TestKeys {

        @Bean
        RSAPublicKey jwtPublicKey() {
            return (RSAPublicKey) KEY_PAIR.getPublic();
        }

        @Bean
        JwtKeySet jwtKeySet(RSAPublicKey jwtPublicKey) {
            return new JwtKeySet(jwtPublicKey);
        }

        @Bean
        JwtProperties jwtProperties() {
            return new JwtProperties("self-service-api", "unused", "unused",
                    Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(15));
        }

        @Bean
        TrialAuthorizationManager trialAuthorizationManager() {
            return new TrialAuthorizationManager();
        }

        @Bean
        TrialExpiredAccessDeniedHandler trialExpiredAccessDeniedHandler(tools.jackson.databind.ObjectMapper objectMapper) {
            return new TrialExpiredAccessDeniedHandler(objectMapper);
        }
    }
}
