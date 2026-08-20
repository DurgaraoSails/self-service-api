package com.sails.ai.selfserviceapi.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
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
        try (InputStream in = resourceLoader.getResource(jwtProperties.privateKeyPath()).getInputStream()) {
            return RsaKeyConverters.pkcs8().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JWT private key from " + jwtProperties.privateKeyPath(), e);
        }
    }

    @Bean
    public RSAPublicKey jwtPublicKey() {
        try (InputStream in = resourceLoader.getResource(jwtProperties.publicKeyPath()).getInputStream()) {
            return RsaKeyConverters.x509().convert(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load JWT public key from " + jwtProperties.publicKeyPath(), e);
        }
    }
}
