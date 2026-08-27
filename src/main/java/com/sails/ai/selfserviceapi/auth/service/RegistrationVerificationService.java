package com.sails.ai.selfserviceapi.auth.service;

import com.sails.ai.selfserviceapi.auth.config.AppFrontendProperties;
import com.sails.ai.selfserviceapi.auth.config.RegistrationVerificationProperties;
import com.sails.ai.selfserviceapi.auth.entity.RegistrationVerificationToken;
import com.sails.ai.selfserviceapi.auth.repository.RegistrationVerificationTokenRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.user.config.AdminProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration completes by the user clicking an emailed link, not by typing a code — a
 * separate mechanism from OtpService's login codes (different token shape, no attempt limiting
 * needed given the token's entropy, and a much longer — 24h default — expiry).
 */
@Service
public class RegistrationVerificationService {

    private static final int TOKEN_BYTES = 32;

    private final RegistrationVerificationTokenRepository tokenRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final RegistrationVerificationProperties properties;
    private final AppFrontendProperties frontendProperties;
    private final AdminProperties adminProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public RegistrationVerificationService(RegistrationVerificationTokenRepository tokenRepository,
                                            UserService userService,
                                            UserRepository userRepository,
                                            EmailService emailService,
                                            RegistrationVerificationProperties properties,
                                            AppFrontendProperties frontendProperties,
                                            AdminProperties adminProperties) {
        this.tokenRepository = tokenRepository;
        this.userService = userService;
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.properties = properties;
        this.frontendProperties = frontendProperties;
        this.adminProperties = adminProperties;
    }

    /** Returns the link's TTL in seconds, for parity with OtpRequestResponse.expiresInSeconds. */
    @Transactional
    public int requestVerification(String email) {
        User user = userService.getEligibleForOtpByEmail(email);
        supersedePending(user.getId());

        String rawToken = generateOpaqueToken();
        RegistrationVerificationToken token = new RegistrationVerificationToken();
        token.setUserId(user.getId());
        token.setTokenHash(hash(rawToken));
        token.setExpiresAt(Instant.now().plus(properties.ttlHours(), ChronoUnit.HOURS));
        tokenRepository.save(token);

        sendVerificationEmail(user, rawToken);
        return properties.ttlHours() * 3600;
    }

    @Transactional
    public User verify(String rawToken) {
        RegistrationVerificationToken token = tokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "REGISTRATION_TOKEN_INVALID",
                        "This verification link is invalid."));

        if (token.getUsedAt() != null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REGISTRATION_TOKEN_INVALID",
                    "This verification link is no longer valid. Register again to get a new one.");
        }
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REGISTRATION_TOKEN_EXPIRED",
                    "This verification link has expired. Register again to get a new one.");
        }

        token.setUsedAt(Instant.now());
        tokenRepository.save(token);

        User user = userService.getById(token.getUserId());
        Instant now = Instant.now();
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(now);
        }
        if (adminProperties.isAdminEmail(user.getEmail()) && !user.getRoles().contains("ADMIN")) {
            List<String> roles = new ArrayList<>(user.getRoles());
            roles.add("ADMIN");
            user.setRoles(roles);
        }
        if (user.getRoles().contains("ADMIN")) {
            user.setTrialStartDate(null);
            user.setTrialEndDate(null);
        }
        user.setLastLoginDate(now);
        return userRepository.save(user);
    }

    private void supersedePending(String userId) {
        for (RegistrationVerificationToken existing : tokenRepository.findAllByUserIdAndUsedAtIsNull(userId)) {
            existing.setUsedAt(Instant.now());
            tokenRepository.save(existing);
        }
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

    private void sendVerificationEmail(User user, String rawToken) {
        String verificationUrl = frontendProperties.url() + "/register/verify?token=" + rawToken;
        emailService.sendTemplate(
                user.getEmail(),
                "Verify your email",
                "registration-verification",
                Map.of(
                        "verificationUrl", verificationUrl,
                        "expiryHours", properties.ttlHours(),
                        "recipientName", user.getFirstName()
                )
        );
    }
}
