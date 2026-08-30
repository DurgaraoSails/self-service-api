package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.user.entity.User;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final RSAPrivateKey jwtPrivateKey;
    private final JwtProperties jwtProperties;

    public JwtService(RSAPrivateKey jwtPrivateKey, JwtProperties jwtProperties) {
        this.jwtPrivateKey = jwtPrivateKey;
        this.jwtProperties = jwtProperties;
    }

    public String issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.accessTokenTtl());

        var builder = Jwts.builder()
                .issuer(jwtProperties.issuer())
                .subject(user.getId())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString());

        if (user.getTenantId() != null) {
            builder.claim("tenantId", user.getTenantId());
        }

        if (user.getTrialEndDate() != null) {
            builder.claim("trialEndDate", user.getTrialEndDate().getEpochSecond());
        }

        return builder.signWith(jwtPrivateKey, Jwts.SIG.RS256).compact();
    }

    public String mintPocAccessToken(String userId, String pocSlug, UUID demoSessionId, Instant expiresAt, String embedMode) {
        return Jwts.builder()
                .subject(userId)
                .audience().add(pocSlug).and()
                .claim("sid", demoSessionId.toString()) // lets the gateway's refresh check hit demo_sessions by id directly
                .claim("embed", embedMode) // lets the gateway pick the session cookie's SameSite value per POC
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(expiresAt))
                .signWith(jwtPrivateKey, Jwts.SIG.RS256)
                .compact();
    }

    public Instant computeExpiry() {
        return Instant.now().plus(jwtProperties.accessTokenTtl().toMinutes(), ChronoUnit.MINUTES);
    }

    public long accessTokenTtlSeconds() {
        return jwtProperties.accessTokenTtl().toSeconds();
    }
}
