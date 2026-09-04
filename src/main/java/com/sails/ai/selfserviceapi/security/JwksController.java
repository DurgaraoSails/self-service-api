package com.sails.ai.selfserviceapi.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.sails.ai.selfserviceapi.generated.api.SystemApi;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the RSA public key as a JWK Set so a POC backend, in any stack, can verify tokens
 * this API issues (access tokens and POST /pocs/{slug}/launch tokens alike) without ever being
 * handed the private key. Built from the {@code RSAPublicKey} bean only, so the JWK Set carries
 * no private-key material regardless of what callers request.
 */
@RestController
public class JwksController implements SystemApi {

    private final RSAPublicKey jwtPublicKey;
    private final JwtProperties jwtProperties;

    public JwksController(RSAPublicKey jwtPublicKey, JwtProperties jwtProperties) {
        this.jwtPublicKey = jwtPublicKey;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public ResponseEntity<Object> getJwks() {
        RSAKey rsaKey = new RSAKey.Builder(jwtPublicKey)
                .keyID(jwtProperties.keyId())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(new JWKSet(rsaKey).toJSONObject());
    }
}
