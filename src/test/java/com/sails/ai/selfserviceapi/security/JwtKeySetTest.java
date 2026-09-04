package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import org.junit.jupiter.api.Test;

class JwtKeySetTest {

    @Test
    void derivesAStableKeyIdFromTheKeyItself() throws Exception {
        RSAPublicKey key = generateKey();

        assertThat(new JwtKeySet(key).keyId()).isEqualTo(new JwtKeySet(key).keyId());
    }

    /** What makes rotation work: a new key cannot accidentally be published under the old id. */
    @Test
    void givesDifferentKeysDifferentIds() throws Exception {
        assertThat(new JwtKeySet(generateKey()).keyId())
                .isNotEqualTo(new JwtKeySet(generateKey()).keyId());
    }

    @Test
    void publishesSignatureUseAndRs256() throws Exception {
        var jwk = new JwtKeySet(generateKey()).publicJwk();

        assertThat(jwk.getKeyUse().identifier()).isEqualTo("sig");
        assertThat(jwk.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwk.getKeyType().getValue()).isEqualTo("RSA");
    }

    /** Nothing secret may reach /.well-known/jwks.json. */
    @Test
    void carriesNoPrivateKeyMaterial() throws Exception {
        assertThat(new JwtKeySet(generateKey()).publicJwk().isPrivate()).isFalse();
    }

    private static RSAPublicKey generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return (RSAPublicKey) keyPair.getPublic();
    }
}
