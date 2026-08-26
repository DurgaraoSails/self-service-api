package com.sails.ai.selfserviceapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.auth.config.OtpProperties;
import com.sails.ai.selfserviceapi.auth.entity.OtpStatus;
import com.sails.ai.selfserviceapi.auth.entity.OtpVerification;
import com.sails.ai.selfserviceapi.auth.repository.OtpAttemptCounter;
import com.sails.ai.selfserviceapi.auth.repository.OtpAttemptSnapshot;
import com.sails.ai.selfserviceapi.auth.repository.OtpVerificationRepository;
import com.sails.ai.selfserviceapi.email.EmailService;
import com.sails.ai.selfserviceapi.user.config.AdminProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class OtpServiceTest {

    private UserService userService;
    private OtpVerificationRepository otpVerificationRepository;
    private OtpAttemptCounter otpAttemptCounter;
    private OtpHasher otpHasher;
    private AdminProperties adminProperties;
    private OtpService otpService;

    @BeforeEach
    void setUp() {
        UserRepository userRepository = Mockito.mock(UserRepository.class);
        userService = Mockito.mock(UserService.class);
        otpVerificationRepository = Mockito.mock(OtpVerificationRepository.class);
        otpAttemptCounter = Mockito.mock(OtpAttemptCounter.class);
        otpHasher = Mockito.mock(OtpHasher.class);
        EmailService emailService = Mockito.mock(EmailService.class);
        adminProperties = Mockito.mock(AdminProperties.class);
        OtpProperties properties = new OtpProperties("secret", 10, 5, 3, 5);
        otpService = new OtpService(userRepository, userService, otpVerificationRepository,
                otpAttemptCounter, otpHasher, properties, emailService, adminProperties);
    }

    private static User activeUser(String email, List<String> roles) {
        User user = new User();
        user.setId("user-1");
        user.setEmail(email);
        user.setStatus(UserStatus.ACTIVE);
        user.setRoles(new ArrayList<>(roles));
        return user;
    }

    private void stubValidPendingOtp(User user) {
        OtpVerification pending = new OtpVerification();
        pending.setId("otp-1");
        pending.setUserId(user.getId());
        pending.setOtpHash("hashed");
        pending.setStatus(OtpStatus.PENDING);
        pending.setExpiresAt(Instant.now().plus(5, ChronoUnit.MINUTES));

        when(userService.getEligibleForOtpByEmail(user.getEmail())).thenReturn(user);
        when(otpVerificationRepository.findFirstByUserIdAndStatusOrderByGeneratedAtDesc(user.getId(), OtpStatus.PENDING))
                .thenReturn(Optional.of(pending));
        when(otpAttemptCounter.incrementAttempt("otp-1")).thenReturn(Optional.of(new OtpAttemptSnapshot(1, "hashed")));
        when(otpHasher.matches("123456", "hashed")).thenReturn(true);
    }

    @Test
    void verifyOtpGrantsAdminRoleWhenEmailIsAnAdminEmail() {
        User user = activeUser("admin@example.com", List.of("USER"));
        stubValidPendingOtp(user);
        when(adminProperties.isAdminEmail("admin@example.com")).thenReturn(true);

        otpService.verifyOtp("admin@example.com", "123456");

        assertThat(user.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    void verifyOtpLeavesRolesUnchangedWhenEmailIsNotAnAdminEmail() {
        User user = activeUser("user@example.com", List.of("USER"));
        stubValidPendingOtp(user);
        when(adminProperties.isAdminEmail("user@example.com")).thenReturn(false);

        otpService.verifyOtp("user@example.com", "123456");

        assertThat(user.getRoles()).containsExactly("USER");
    }

    @Test
    void verifyOtpDoesNotDuplicateTheAdminRoleOnRepeatedLogins() {
        User user = activeUser("admin@example.com", List.of("USER", "ADMIN"));
        stubValidPendingOtp(user);
        when(adminProperties.isAdminEmail("admin@example.com")).thenReturn(true);

        otpService.verifyOtp("admin@example.com", "123456");

        assertThat(user.getRoles()).containsExactly("USER", "ADMIN");
    }
}
