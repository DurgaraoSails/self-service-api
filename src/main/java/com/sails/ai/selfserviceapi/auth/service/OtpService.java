package com.sails.ai.selfserviceapi.auth.service;

import com.sails.ai.selfserviceapi.auth.config.OtpProperties;
import com.sails.ai.selfserviceapi.auth.entity.OtpStatus;
import com.sails.ai.selfserviceapi.auth.entity.OtpVerification;
import com.sails.ai.selfserviceapi.auth.repository.OtpAttemptCounter;
import com.sails.ai.selfserviceapi.auth.repository.OtpAttemptSnapshot;
import com.sails.ai.selfserviceapi.auth.repository.OtpVerificationRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OtpService {

    private final UserRepository userRepository;
    private final OtpVerificationRepository otpVerificationRepository;
    private final OtpAttemptCounter otpAttemptCounter;
    private final OtpHasher otpHasher;
    private final OtpProperties properties;
    private final EmailService emailService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OtpService(UserRepository userRepository,
                       OtpVerificationRepository otpVerificationRepository,
                       OtpAttemptCounter otpAttemptCounter,
                       OtpHasher otpHasher,
                       OtpProperties properties,
                       EmailService emailService) {
        this.userRepository = userRepository;
        this.otpVerificationRepository = otpVerificationRepository;
        this.otpAttemptCounter = otpAttemptCounter;
        this.otpHasher = otpHasher;
        this.properties = properties;
        this.emailService = emailService;
    }

    @Transactional
    public int requestOtp(String email) {
        User user = findActiveUserOrThrow(email);
        enforceHourlyRateLimit(user.getId());
        supersedePendingOtp(user.getId());

        IssuedOtp issued = issueNewOtp(user.getId(), 0);
        sendOtpEmail(user, issued.rawCode());
        return properties.expiryMinutes() * 60;
    }

    @Transactional
    public int resendOtp(String email) {
        User user = findActiveUserOrThrow(email);

        OtpVerification current = otpVerificationRepository
                .findFirstByUserIdAndStatusOrderByGeneratedAtDesc(user.getId(), OtpStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND",
                        "No active login code to resend. Request a new one."));

        if (current.getResendCount() >= properties.maxResends()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_LIMIT_EXCEEDED",
                    "Resend limit reached. Request a new code.");
        }

        enforceHourlyRateLimit(user.getId());

        current.setStatus(OtpStatus.EXPIRED);
        otpVerificationRepository.save(current);

        IssuedOtp issued = issueNewOtp(user.getId(), current.getResendCount() + 1);
        sendOtpEmail(user, issued.rawCode());
        return properties.expiryMinutes() * 60;
    }

    @Transactional
    public String verifyOtp(String email, String code) {
        User user = findActiveUserOrThrow(email);

        OtpVerification current = otpVerificationRepository
                .findFirstByUserIdAndStatusOrderByGeneratedAtDesc(user.getId(), OtpStatus.PENDING)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND",
                        "No active login code. Request a new one."));

        if (Instant.now().isAfter(current.getExpiresAt())) {
            current.setStatus(OtpStatus.EXPIRED);
            otpVerificationRepository.save(current);
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_EXPIRED", "This code has expired. Request a new one.");
        }

        OtpAttemptSnapshot snapshot = otpAttemptCounter.incrementAttempt(current.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OTP_NOT_FOUND",
                        "No active login code. Request a new one."));

        if (snapshot.attemptCount() > properties.maxAttempts()) {
            current.setStatus(OtpStatus.LOCKED);
            otpVerificationRepository.save(current);
            throw new ApiException(HttpStatus.LOCKED, "OTP_LOCKED", "Too many failed attempts. Request a new code.");
        }

        if (!otpHasher.matches(code, snapshot.otpHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OTP_INVALID", "Incorrect code.");
        }

        current.setStatus(OtpStatus.VERIFIED);
        current.setVerifiedAt(Instant.now());
        otpVerificationRepository.save(current);

        user.setLastLoginDate(Instant.now());
        userRepository.save(user);

        return user.getId();
    }

    private User findActiveUserOrThrow(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "No account found for this email."));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email before requesting a login code.");
        }
        return user;
    }

    private void enforceHourlyRateLimit(String userId) {
        Instant windowStart = Instant.now().minus(1, ChronoUnit.HOURS);
        long requestsInWindow = otpVerificationRepository.countByUserIdAndGeneratedAtAfter(userId, windowStart);
        if (requestsInWindow >= properties.maxRequestsPerHour()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED", "Too many login code requests. Try again later.");
        }
    }

    private void supersedePendingOtp(String userId) {
        otpVerificationRepository.findFirstByUserIdAndStatusOrderByGeneratedAtDesc(userId, OtpStatus.PENDING)
                .ifPresent(existing -> {
                    existing.setStatus(OtpStatus.EXPIRED);
                    otpVerificationRepository.save(existing);
                });
    }

    private IssuedOtp issueNewOtp(String userId, int startingResendCount) {
        String code = generateCode();
        OtpVerification otp = new OtpVerification();
        otp.setUserId(userId);
        otp.setOtpHash(otpHasher.hash(code));
        Instant now = Instant.now();
        otp.setGeneratedAt(now);
        otp.setExpiresAt(now.plus(properties.expiryMinutes(), ChronoUnit.MINUTES));
        otp.setResendCount(startingResendCount);
        otp.setStatus(OtpStatus.PENDING);
        otpVerificationRepository.save(otp);
        return new IssuedOtp(code);
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private void sendOtpEmail(User user, String code) {
        emailService.send(
                user.getEmail(),
                "Your verification code",
                "Your one-time login code is " + code + ". It expires in " + properties.expiryMinutes() + " minutes."
        );
    }

    private record IssuedOtp(String rawCode) {
    }
}
