package com.sails.ai.selfserviceapi.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String issuer,
        String privateKeyPath,
        String publicKeyPath,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration pocTokenTtl,
        String keyId,
        /**
         * Extra public keys published at {@code /.well-known/jwks.json} alongside the current
         * signing key, verification-only — never used to sign anything.
         *
         * <p>This is what makes rotation gradual instead of an instant hard cutover: publish a new
         * key here before it ever signs anything, so every JWKS consumer has had a chance to see it
         * (a live poll or an unknown-{@code kid} refetch); once you've waited out the longer of
         * {@code accessTokenTtl}/{@code pocTokenTtl}, swap {@code privateKeyPath}/{@code
         * publicKeyPath} to that key and move the <em>old</em> key pair's public half in here
         * instead — now it's the straggler tokens signed just before cutover that stay verifiable.
         * Drop it from this list for good after one more TTL window.
         */
        List<String> additionalPublicKeyPaths
) {
    public JwtProperties {
        additionalPublicKeyPaths = additionalPublicKeyPaths == null ? List.of() : additionalPublicKeyPaths;
    }
}
