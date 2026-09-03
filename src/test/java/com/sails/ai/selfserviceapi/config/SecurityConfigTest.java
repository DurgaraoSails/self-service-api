package com.sails.ai.selfserviceapi.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sails.ai.selfserviceapi.file.controller.PocFilesController;
import com.sails.ai.selfserviceapi.file.controller.PortalFilesController;
import com.sails.ai.selfserviceapi.file.service.FileService;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The wiring nothing else covers. Two classes of token, signed by the same key with the same
 * issuer, are meant to work on disjoint sets of endpoints — the portal's decoder on one filter
 * chain, the POC-files decoder on another — and a mistake in that split is invisible until the
 * wrong token reaches something it should never have reached. This exercises both directions on
 * both chains: a portal endpoint with a POC token, and a POC-files endpoint with a portal token.
 *
 * <p>{@link JwksController}, {@link PocFilesController} and {@link PortalFilesController} are the
 * only controllers registered. That is what makes the portal-side assertions against {@code
 * /users/me} legible: a request that authenticates reaches a route this slice does not serve and
 * gets 404, while one that fails to authenticate is stopped at the filter and gets 401 — two
 * different codes for two different failures. {@code /pocs/{id}/files} is served for real, since
 * proving it stays on the portal's chain needs a route that actually responds.
 */
@WebMvcTest(controllers = {JwksController.class, PocFilesController.class, PortalFilesController.class})
@Import({SecurityConfig.class, CorsConfig.class, SecurityConfigTest.TestKeys.class})
class SecurityConfigTest {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

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
     * The one that matters on the portal side. Same key, same issuer, still unusable here —
     * otherwise a token handed to JavaScript on a POC's origin would read the portal user's own
     * profile.
     */
    @Test
    void rejectsAPocScopedTokenOnAPortalEndpoint() throws Exception {
        mockMvc.perform(get("/users/me").header("Authorization", "Bearer " + pocToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsARequestWithNoTokenOnPocFiles() throws Exception {
        mockMvc.perform(get("/poc-files"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * The mirror image, and just as load-bearing: the POC-facing endpoints take no user or POC
     * parameter — everything comes from the token's claims — so a portal access token succeeding
     * here would have nothing downstream left to stop it from reading whichever (user, POC) pair
     * a caller cared to try.
     */
    @Test
    void rejectsAUserAccessTokenOnAPocFilesEndpoint() throws Exception {
        mockMvc.perform(get("/poc-files").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAPocScopedTokenOnAPocFilesEndpoint() throws Exception {
        when(fileService.list(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/poc-files").header("Authorization", "Bearer " + pocToken()))
                .andExpect(status().isOk());
    }

    /**
     * The portal-facing surface (Phase 4) shares nothing with /poc-files except the FileService
     * underneath it — it must stay on the portal's own chain, gated the same as any other portal
     * route, not accept the token minted for the POC-facing one.
     */
    @Test
    void rejectsAPocScopedTokenOnAPortalFilesEndpoint() throws Exception {
        mockMvc.perform(get("/pocs/4/files").header("Authorization", "Bearer " + pocToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsAUserAccessTokenOnAPortalFilesEndpoint() throws Exception {
        when(fileService.list(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/pocs/4/files").header("Authorization", "Bearer " + userToken()))
                .andExpect(status().isOk());
    }

    private static String userToken() {
        return baseToken().compact();
    }

    private static String pocToken() {
        return baseToken()
                .audience().add(PocAudience.forSlug("contract-agent")).and()
                .claim("pocId", 4L)
                .compact();
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
