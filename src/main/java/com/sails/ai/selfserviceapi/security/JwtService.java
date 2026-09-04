package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.user.entity.User;
import io.jsonwebtoken.Jwts;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
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
                .header().keyId(jwtProperties.keyId()).and()
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

    /**
     * Mints a short-lived token scoped to exactly one POC via the {@code aud} claim
     * ({@code poc:<slug>}), for {@code POST /pocs/{slug}/launch}. Never carries a refresh token
     * counterpart — a POC re-requests one from the portal instead of refreshing itself, which is
     * what lets trial expiry and logout cut off POC access with no revocation list.
     */
    public String issuePocToken(User user, String slug) {
        Instant now = Instant.now();
        Instant expiry = now.plus(jwtProperties.pocTokenTtl());

        var builder = Jwts.builder()
                .header().keyId(jwtProperties.keyId()).and()
                .issuer(jwtProperties.issuer())
                .subject(user.getId())
                .audience().single("poc:" + slug)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString());

        if (user.getTrialEndDate() != null) {
            builder.claim("trialEndDate", user.getTrialEndDate().getEpochSecond());
        }

        return builder.signWith(jwtPrivateKey, Jwts.SIG.RS256).compact();
    }

    public long accessTokenTtlSeconds() {
        return jwtProperties.accessTokenTtl().toSeconds();
    }

    public long pocTokenTtlSeconds() {
        return jwtProperties.pocTokenTtl().toSeconds();
    }
}
