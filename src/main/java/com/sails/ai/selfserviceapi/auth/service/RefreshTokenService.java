package com.sails.ai.selfserviceapi.auth.service;

import com.sails.ai.selfserviceapi.auth.entity.RefreshToken;
import com.sails.ai.selfserviceapi.auth.exception.InvalidRefreshTokenException;
import com.sails.ai.selfserviceapi.auth.exception.RefreshTokenReuseDetectedException;
import com.sails.ai.selfserviceapi.auth.repository.RefreshTokenRepository;
import com.sails.ai.selfserviceapi.security.JwtProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository, JwtProperties jwtProperties) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProperties = jwtProperties;
    }

    public record Issued(String rawToken, RefreshToken entity) {
    }

    @Transactional
    public Issued issue(User user) {
        String rawToken = generateOpaqueToken();
        RefreshToken entity = new RefreshToken();
        entity.setUserId(user.getId());
        entity.setTokenHash(hash(rawToken));
        entity.setExpiresAt(Instant.now().plus(jwtProperties.refreshTokenTtl()));
        entity = refreshTokenRepository.save(entity);
        return new Issued(rawToken, entity);
    }

    @Transactional
    public RefreshToken validateAndConsume(String rawToken) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new InvalidRefreshTokenException("Refresh token not found"));

        if (token.getRevokedAt() != null) {
            log.warn("Refresh token reuse detected for user {} — revoking token family", token.getUserId());
            revokeAllForUser(token.getUserId());
            throw new RefreshTokenReuseDetectedException("Refresh token has already been used");
        }

        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw new InvalidRefreshTokenException("Refresh token has expired");
        }

        return token;
    }

    @Transactional
    public void rotate(RefreshToken oldToken, RefreshToken newToken) {
        oldToken.setRevokedAt(Instant.now());
        oldToken.setReplacedByTokenId(newToken.getId());
        refreshTokenRepository.save(oldToken);
    }

    @Transactional
    public void revokeAllForUser(String userId) {
        Instant now = Instant.now();
        for (RefreshToken token : refreshTokenRepository.findAllByUserIdAndRevokedAtIsNull(userId)) {
            token.setRevokedAt(now);
            refreshTokenRepository.save(token);
        }
    }

    @Transactional
    public void revokeByRawToken(String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    private String generateOpaqueToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
