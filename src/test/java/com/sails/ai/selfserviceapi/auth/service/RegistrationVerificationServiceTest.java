package com.sails.ai.selfserviceapi.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class RegistrationVerificationServiceTest {

    private RegistrationVerificationTokenRepository tokenRepository;
    private UserService userService;
    private UserRepository userRepository;
    private EmailService emailService;
    private AdminProperties adminProperties;
    private RegistrationVerificationService service;

    @BeforeEach
    void setUp() {
        tokenRepository = Mockito.mock(RegistrationVerificationTokenRepository.class);
        userService = Mockito.mock(UserService.class);
        userRepository = Mockito.mock(UserRepository.class);
        emailService = Mockito.mock(EmailService.class);
        adminProperties = Mockito.mock(AdminProperties.class);
        RegistrationVerificationProperties properties = new RegistrationVerificationProperties(24);
        AppFrontendProperties frontendProperties = new AppFrontendProperties("https://portal.example.com");
        service = new RegistrationVerificationService(tokenRepository, userService, userRepository, emailService,
                properties, frontendProperties, adminProperties);

        when(tokenRepository.save(any(RegistrationVerificationToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static User pendingUser(String email) {
        User user = new User();
        user.setId("user-1");
        user.setEmail(email);
        user.setFirstName("Jane");
        user.setStatus(UserStatus.PENDING_VERIFICATION);
        user.setRoles(new ArrayList<>(List.of("USER")));
        return user;
    }

    @Test
    void requestVerificationEmailsALinkContainingTheRawTokenAndReturnsTheTtlInSeconds() {
        User user = pendingUser("jane.doe@example.com");
        when(userService.getEligibleForOtpByEmail("jane.doe@example.com")).thenReturn(user);
        when(tokenRepository.findAllByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of());

        int expiresInSeconds = service.requestVerification("jane.doe@example.com");

        assertThat(expiresInSeconds).isEqualTo(24 * 3600);

        ArgumentCaptor<RegistrationVerificationToken> tokenCaptor = ArgumentCaptor.forClass(RegistrationVerificationToken.class);
        verify(tokenRepository).save(tokenCaptor.capture());
        RegistrationVerificationToken saved = tokenCaptor.getValue();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getExpiresAt()).isCloseTo(Instant.now().plus(24, ChronoUnit.HOURS), within(2, ChronoUnit.SECONDS));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> variablesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(emailService).sendTemplate(anyString(), anyString(), anyString(), variablesCaptor.capture());
        String verificationUrl = (String) variablesCaptor.getValue().get("verificationUrl");
        assertThat(verificationUrl).startsWith("https://portal.example.com/register/verify?token=");
        assertThat(verificationUrl).doesNotContain(saved.getTokenHash());
    }

    @Test
    void requestVerificationSupersedesAnyPreviousPendingToken() {
        User user = pendingUser("jane.doe@example.com");
        when(userService.getEligibleForOtpByEmail("jane.doe@example.com")).thenReturn(user);
        RegistrationVerificationToken previous = new RegistrationVerificationToken();
        previous.setUserId("user-1");
        when(tokenRepository.findAllByUserIdAndUsedAtIsNull("user-1")).thenReturn(List.of(previous));

        service.requestVerification("jane.doe@example.com");

        assertThat(previous.getUsedAt()).isNotNull();
        verify(tokenRepository, times(2)).save(any(RegistrationVerificationToken.class));
    }

    @Test
    void verifyActivatesAPendingUserAndMarksTheTokenUsed() {
        User user = pendingUser("jane.doe@example.com");
        RegistrationVerificationToken token = new RegistrationVerificationToken();
        token.setUserId("user-1");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userService.getById("user-1")).thenReturn(user);
        when(adminProperties.isAdminEmail("jane.doe@example.com")).thenReturn(false);

        User verified = service.verify("raw-token");

        assertThat(verified.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(verified.getEmailVerifiedAt()).isNotNull();
        assertThat(verified.getLastLoginDate()).isNotNull();
        assertThat(token.getUsedAt()).isNotNull();
    }

    @Test
    void verifyGrantsAdminRoleAndExemptsFromTrialWhenEmailIsAnAdminEmail() {
        User user = pendingUser("admin@example.com");
        user.setTrialStartDate(Instant.now());
        user.setTrialEndDate(Instant.now().plus(14, ChronoUnit.DAYS));
        RegistrationVerificationToken token = new RegistrationVerificationToken();
        token.setUserId("user-1");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));
        when(userService.getById("user-1")).thenReturn(user);
        when(adminProperties.isAdminEmail("admin@example.com")).thenReturn(true);

        User verified = service.verify("raw-token");

        assertThat(verified.getRoles()).containsExactlyInAnyOrder("USER", "ADMIN");
        assertThat(verified.getTrialStartDate()).isNull();
        assertThat(verified.getTrialEndDate()).isNull();
    }

    @Test
    void verifyThrowsWhenTokenNotFound() {
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("unknown-token"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("REGISTRATION_TOKEN_INVALID");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void verifyThrowsWhenTokenAlreadyUsed() {
        RegistrationVerificationToken token = new RegistrationVerificationToken();
        token.setUserId("user-1");
        token.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        token.setUsedAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify("raw-token"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("REGISTRATION_TOKEN_INVALID");
    }

    @Test
    void verifyThrowsWhenTokenExpired() {
        RegistrationVerificationToken token = new RegistrationVerificationToken();
        token.setUserId("user-1");
        token.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        when(tokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> service.verify("raw-token"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("REGISTRATION_TOKEN_EXPIRED");
    }
}
