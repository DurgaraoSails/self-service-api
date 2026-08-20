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

        return builder.signWith(jwtPrivateKey, Jwts.SIG.RS256).compact();
    }

    public long accessTokenTtlSeconds() {
        return jwtProperties.accessTokenTtl().toSeconds();
    }
}
