package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.user.entity.ThemeMode;
import com.sails.ai.selfserviceapi.user.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private JwtService jwtService;
    private JwtKeySet keySet;
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties properties = new JwtProperties(
                "self-service-api", "unused", "unused",
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(15),"1");
        keySet = new JwtKeySet(publicKey);
        jwtService = new JwtService((RSAPrivateKey) keyPair.getPrivate(), properties, keySet);
    }

    @Test
    void includesTrialEndDateClaimWhenUserHasATrialEndDate() {
        User user = baseUser();
        Instant trialEnd = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        user.setTrialEndDate(trialEnd);

        Claims claims = parse(jwtService.issueAccessToken(user));

        assertThat(claims.get("trialEndDate", Long.class)).isEqualTo(trialEnd.getEpochSecond());
    }

    @Test
    void omitsTrialEndDateClaimWhenUserHasNoTrialEndDate() {
        User user = baseUser();
        user.setTrialEndDate(null);

        Claims claims = parse(jwtService.issueAccessToken(user));

        assertThat(claims.get("trialEndDate")).isNull();
    }

    @Test
    void stampsEveryAccessTokenWithTheKeyId() {
        String kid = header(jwtService.issueAccessToken(baseUser()));

        assertThat(kid).isEqualTo(keySet.keyId());
    }

    @Test
    void pocTokenCarriesTheKeyIdTheJwksEndpointPublishes() {
        assertThat(header(jwtService.issuePocToken(baseUser(), poc()))).isEqualTo(keySet.keyId());
    }

    @Test
    void pocTokenIsScopedToThatPocsAudience() {
        Claims claims = parse(jwtService.issuePocToken(baseUser(), poc()));

        assertThat(claims.getAudience()).containsExactly("poc:contract-agent");
        assertThat(claims.get("pocId", Long.class)).isEqualTo(4L);
        assertThat(claims.getSubject()).isEqualTo("01JABC123XYZ");
    }

    /** A POC must not inherit the authority of whoever launched it. */
    @Test
    void pocTokenCarriesNoRoles() {
        User admin = baseUser();
        admin.setRoles(List.of("USER", "ADMIN"));

        Claims claims = parse(jwtService.issuePocToken(admin, poc()));

        assertThat(claims.get("roles")).isNull();
    }

    @Test
    void pocTokenCarriesNoEmail() {
        assertThat(parse(jwtService.issuePocToken(baseUser(), poc())).get("email")).isNull();
    }

    @Test
    void pocTokenCarriesDisplayNameAndTheme() {
        User user = baseUser();
        user.setDisplayName("Janey");
        user.setTheme(ThemeMode.DARK);

        Claims claims = parse(jwtService.issuePocToken(user, poc()));

        assertThat(claims.get("name", String.class)).isEqualTo("Janey");
        assertThat(claims.get("theme", String.class)).isEqualTo("DARK");
    }

    @Test
    void pocTokenFallsBackToTheUsersNameWhenNoDisplayNameIsSet() {
        User user = baseUser();
        user.setDisplayName(null);

        assertThat(parse(jwtService.issuePocToken(user, poc())).get("name", String.class))
                .isEqualTo("Jane Doe");
    }

    /** So the existing trial gate applies to a POC token too, rather than a second expiry rule. */
    @Test
    void pocTokenCarriesTrialEndDate() {
        User user = baseUser();
        Instant trialEnd = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        user.setTrialEndDate(trialEnd);

        assertThat(parse(jwtService.issuePocToken(user, poc())).get("trialEndDate", Long.class))
                .isEqualTo(trialEnd.getEpochSecond());
    }

    @Test
    void pocTokenExpiresFarSoonerThanAnAccessToken() {
        Claims pocClaims = parse(jwtService.issuePocToken(baseUser(), poc()));
        Claims accessClaims = parse(jwtService.issueAccessToken(baseUser()));

        assertThat(pocClaims.getExpiration()).isBefore(accessClaims.getExpiration());
        assertThat(jwtService.pocTokenTtlSeconds()).isEqualTo(900L);
    }

    private String header(String token) {
        return (String) Jwts.parser().verifyWith(publicKey).build()
                .parseSignedClaims(token).getHeader().get("kid");
    }

    private Poc poc() {
        Poc poc = new Poc();
        poc.setId(4L);
        poc.setSlug("contract-agent");
        poc.setAppUrl("https://contract-agent.example.run.app");
        return poc;
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    private User baseUser() {
        User user = new User();
        user.setId("01JABC123XYZ");
        user.setFirstName("Jane");
        user.setLastName("Doe");
        user.setEmail("jane.doe@example.com");
        user.setRoles(List.of("USER"));
        return user;
    }
}
