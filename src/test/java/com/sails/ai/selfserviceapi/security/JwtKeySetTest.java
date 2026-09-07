package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtKeySetTest {

    @Test
    void derivesAStableKeyIdFromTheKeyItself() throws Exception {
        RSAPublicKey key = generateKey();

        assertThat(new JwtKeySet(key, List.of()).keyId()).isEqualTo(new JwtKeySet(key, List.of()).keyId());
    }

    /** What makes rotation work: a new key cannot accidentally be published under the old id. */
    @Test
    void givesDifferentKeysDifferentIds() throws Exception {
        assertThat(new JwtKeySet(generateKey(), List.of()).keyId())
                .isNotEqualTo(new JwtKeySet(generateKey(), List.of()).keyId());
    }

    @Test
    void publishesSignatureUseAndRs256() throws Exception {
        var jwk = new JwtKeySet(generateKey(), List.of()).publicJwk();

        assertThat(jwk.getKeyUse().identifier()).isEqualTo("sig");
        assertThat(jwk.getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwk.getKeyType().getValue()).isEqualTo("RSA");
    }

    /** Nothing secret may reach /.well-known/jwks.json. */
    @Test
    void carriesNoPrivateKeyMaterial() throws Exception {
        assertThat(new JwtKeySet(generateKey(), List.of()).publicJwk().isPrivate()).isFalse();
    }

    @Test
    void withNoAdditionalKeysPublishesOnlyTheSigningKey() throws Exception {
        JwtKeySet keySet = new JwtKeySet(generateKey(), List.of());

        assertThat(keySet.allPublicJwks()).containsExactly(keySet.publicJwk());
    }

    /**
     * This is what makes a gradual rotation possible: a key published here (e.g. a not-yet-active
     * "next" key, or a just-retired one still covering straggler tokens) verifies without ever
     * being the one {@link JwtKeySet#keyId()} points at.
     */
    @Test
    void publishesAdditionalKeysAlongsideTheSigningKeyWithoutChangingWhatSigns() throws Exception {
        RSAPublicKey signingKey = generateKey();
        RSAPublicKey nextKey = generateKey();

        JwtKeySet keySet = new JwtKeySet(signingKey, List.of(nextKey));

        assertThat(keySet.allPublicJwks()).hasSize(2);
        assertThat(keySet.allPublicJwks().get(0)).isEqualTo(keySet.publicJwk());
        assertThat(keySet.keyId()).isEqualTo(keySet.publicJwk().getKeyID());
    }

    @Test
    void everyPublishedKeyGetsItsOwnDistinctKeyId() throws Exception {
        JwtKeySet keySet = new JwtKeySet(generateKey(), List.of(generateKey(), generateKey()));

        List<String> keyIds = keySet.allPublicJwks().stream().map(jwk -> jwk.getKeyID()).toList();

        assertThat(keyIds).doesNotHaveDuplicates();
    }

    private static RSAPublicKey generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        return (RSAPublicKey) keyPair.getPublic();
    }
}
