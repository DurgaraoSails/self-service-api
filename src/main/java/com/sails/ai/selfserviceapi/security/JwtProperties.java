package com.sails.ai.selfserviceapi.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String privateKeyPath,
        String publicKeyPath,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,

        /**
         * Lifetime of a POC-scoped token. Much shorter than an access token's: it lives in
         * JavaScript on a POC's own origin, and the portal is the only thing that can refresh it,
         * so a short life is what makes portal logout stop POC access without any revocation
         * infrastructure.
         */
        Duration pocTokenTtl
) {
}
