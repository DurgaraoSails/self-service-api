package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

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
    private RSAPublicKey publicKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) keyPair.getPublic();

        JwtProperties properties = new JwtProperties(
                "self-service-api", "unused", "unused", Duration.ofMinutes(30), Duration.ofDays(7),
                Duration.ofMinutes(10), "1");
        jwtService = new JwtService((RSAPrivateKey) keyPair.getPrivate(), properties);
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
    void accessTokenCarriesTheConfiguredKeyId() {
        String token = jwtService.issueAccessToken(baseUser());

        String kid = Jwts.parser().verifyWith(publicKey).build()
                .parseSignedClaims(token).getHeader().getKeyId();

        assertThat(kid).isEqualTo("1");
    }

    @Test
    void pocTokenScopesAudienceToTheGivenSlug() {
        Claims claims = parse(jwtService.issuePocToken(baseUser(), "contract-agent"));

        assertThat(claims.getAudience()).containsExactly("poc:contract-agent");
    }

    @Test
    void pocTokenExpiresMuchSoonerThanAnAccessToken() {
        User user = baseUser();
        Instant beforeIssue = Instant.now();

        Claims accessClaims = parse(jwtService.issueAccessToken(user));
        Claims pocClaims = parse(jwtService.issuePocToken(user, "contract-agent"));

        Instant accessExpiry = accessClaims.getExpiration().toInstant();
        Instant pocExpiry = pocClaims.getExpiration().toInstant();
        assertThat(pocExpiry).isBefore(accessExpiry);
        assertThat(Duration.between(beforeIssue, pocExpiry)).isCloseTo(Duration.ofMinutes(10), Duration.ofSeconds(5));
    }

    @Test
    void pocTokenCarriesTrialEndDateWhenPresent() {
        User user = baseUser();
        Instant trialEnd = Instant.now().plus(14, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        user.setTrialEndDate(trialEnd);

        Claims claims = parse(jwtService.issuePocToken(user, "contract-agent"));

        assertThat(claims.get("trialEndDate", Long.class)).isEqualTo(trialEnd.getEpochSecond());
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(publicKey).build().parseSignedClaims(token).getPayload();
    }

    private User baseUser() {
        User user = new User();
        user.setId("01JABC123XYZ");
        user.setEmail("jane.doe@example.com");
        user.setRoles(List.of("USER"));
        return user;
    }
}
