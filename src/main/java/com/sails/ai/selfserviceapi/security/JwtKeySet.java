package com.sails.ai.selfserviceapi.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import java.security.interfaces.RSAPublicKey;
import org.springframework.stereotype.Component;

/**
 * The signing key's public half, in JWK form, plus the key id that identifies it.
 *
 * <p>One object owns both because the {@code kid} in an issued token's header and the {@code kid}
 * published at {@code /.well-known/jwks.json} have to be the same string — a verifier selects its
 * key by matching them, so if they ever disagreed every POC would fail to verify every token, and
 * nothing else would look wrong.
 *
 * <p>The id is the key's RFC 7638 thumbprint rather than a configured name. A thumbprint is derived
 * from the key material itself, so a new key gets a new id automatically: there is no step in a
 * rotation where someone must remember to change it, and no way to publish two different keys
 * under one id.
 *
 * <p>Nimbus is already on the classpath — Spring Security's resource server depends on it — so
 * this adds no dependency, in keeping with the restraint the deploy pipeline set about SDKs.
 */
@Component
public class JwtKeySet {

    private final RSAKey publicJwk;

    public JwtKeySet(RSAPublicKey jwtPublicKey) {
        try {
            this.publicJwk = new RSAKey.Builder(jwtPublicKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyIDFromThumbprint()
                    .build();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to derive a key id from the JWT public key", e);
        }
    }

    /** Goes in the header of every token this API issues. */
    public String keyId() {
        return publicJwk.getKeyID();
    }

    /** Public material only — {@code RSAKey.Builder(RSAPublicKey)} cannot carry a private half. */
    public RSAKey publicJwk() {
        return publicJwk;
    }
}
