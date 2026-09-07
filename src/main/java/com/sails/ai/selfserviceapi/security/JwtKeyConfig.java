package com.sails.ai.selfserviceapi.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.converter.RsaKeyConverters;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtKeyConfig {

    private final ResourceLoader resourceLoader;
    private final JwtProperties jwtProperties;

    public JwtKeyConfig(ResourceLoader resourceLoader, JwtProperties jwtProperties) {
        this.resourceLoader = resourceLoader;
        this.jwtProperties = jwtProperties;
    }

    @Bean
    public RSAPrivateKey jwtPrivateKey() {
        return loadPrivateKey(jwtProperties.privateKeyPath());
    }

    @Bean
    public RSAPublicKey jwtPublicKey() {
        return loadPublicKey(jwtProperties.publicKeyPath());
    }

    /**
     * Verification-only keys published in JWKS alongside {@link #jwtPublicKey()} — see
     * {@link JwtProperties#additionalPublicKeyPaths()} for how these are used during a rotation.
     * Empty by default, in which case JWKS behaves exactly as it did before rotation support.
     */
    @Bean
    public List<RSAPublicKey> additionalJwtPublicKeys() {
        return jwtProperties.additionalPublicKeyPaths().stream()
                .map(this::loadPublicKey)
                .toList();
    }

    private RSAPrivateKey loadPrivateKey(String path) {
        try (InputStream in = resourceLoader.getResource(path).getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JWT private key from " + path, e);
        }
    }

    private RSAPublicKey loadPublicKey(String path) {
        try (InputStream in = resourceLoader.getResource(path).getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JWT public key from " + path, e);
        }
    }
}
