package com.sails.ai.selfserviceapi.security;

import com.nimbusds.jose.jwk.RSAKey;
import com.sails.ai.selfserviceapi.generated.api.WellKnownApi;
import com.sails.ai.selfserviceapi.generated.model.Jwk;
import com.sails.ai.selfserviceapi.generated.model.JwkSet;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public key so a POC backend, in whatever stack its team chose, can verify a
 * POC-scoped token without this service handing out a shared secret.
 */
@RestController
public class JwksController implements WellKnownApi {

    /**
     * Long enough that POCs are not refetching this on every request, and harmless to a rotation:
     * a JWKS client refetches when it meets a {@code kid} it does not know, so publishing the new
     * key and then signing with it costs one extra fetch rather than a coordinated cutover.
     */
    private static final Duration CACHE_MAX_AGE = Duration.ofHours(1);

    private final JwtKeySet jwtKeySet;

    public JwksController(JwtKeySet jwtKeySet) {
        this.jwtKeySet = jwtKeySet;
    }

    @Override
    public ResponseEntity<JwkSet> getJwks() {
        RSAKey key = jwtKeySet.publicJwk();

        Jwk jwk = new Jwk(
                key.getKeyType().getValue(),
                key.getKeyID(),
                key.getModulus().toString(),
                key.getPublicExponent().toString())
                .use(key.getKeyUse().identifier())
                .alg(key.getAlgorithm().getName());

        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_MAX_AGE).cachePublic())
                .body(new JwkSet(List.of(jwk)));
    }
}
