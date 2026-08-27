package com.sails.ai.selfserviceapi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.config.TrialProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.exception.UserAlreadyExistsException;
import com.sails.ai.selfserviceapi.user.exception.UserNotFoundException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

class UserServiceTest {

    private UserRepository userRepository;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        EmailDomainValidator emailDomainValidator = Mockito.mock(EmailDomainValidator.class);
        userService = new UserService(userRepository, new TrialProperties(14), emailDomainValidator);
    }

    @Test
    void registerUserCreatesANewPendingUserWhenNoAccountExists() {
        when(userRepository.findByEmail("jane.doe@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.registerUser("Jane", "Doe", "Acme Corp", "Engineer", "United States", "jane.doe@example.com");

        assertThat(user.getEmail()).isEqualTo("jane.doe@example.com");
        assertThat(user.getFirstName()).isEqualTo("Jane");
        assertThat(user.getRoles()).containsExactly("USER");
    }

    @Test
    void registerUserThrowsWhenTheEmailBelongsToAVerifiedUser() {
        User verified = new User();
        verified.setId("existing-id");
        verified.setEmail("jane.doe@example.com");
        verified.setStatus(UserStatus.ACTIVE);
        verified.setEmailVerifiedAt(Instant.now());
        when(userRepository.findByEmail("jane.doe@example.com")).thenReturn(Optional.of(verified));

        assertThatThrownBy(() ->
                userService.registerUser("Jane", "Doe", "Acme Corp", "Engineer", "United States", "jane.doe@example.com"))
                .isInstanceOf(UserAlreadyExistsException.class);
    }

    @Test
    void registerUserOverwritesAnUnverifiedUserInPlaceInsteadOfThrowing() {
        User pending = new User();
        pending.setId("pending-id");
        pending.setEmail("jane.doe@example.com");
        pending.setFirstName("OldFirstName");
        pending.setStatus(UserStatus.PENDING_VERIFICATION);
        pending.setEmailVerifiedAt(null);
        when(userRepository.findByEmail("jane.doe@example.com")).thenReturn(Optional.of(pending));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User user = userService.registerUser("Jane", "Doe", "Acme Corp", "Engineer", "United States", "jane.doe@example.com");

        assertThat(user.getId()).isEqualTo("pending-id");
        assertThat(user.getFirstName()).isEqualTo("Jane");
    }

    private static User userWithId(String id) {
        User user = new User();
        user.setId(id);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }

    @SuppressWarnings("unchecked")
    private void stubFindAll(Page<User> page) {
        when(userRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);
    }

    @Test
    void listCustomersDelegatesToRepositoryAndReturnsThePage() {
        Pageable pageable = PageRequest.of(0, 30);
        Page<User> page = new PageImpl<>(List.of(userWithId("u1")));
        stubFindAll(page);

        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-08-31T00:00:00Z");
        assertThat(userService.listCustomers(false, from, to, null, pageable)).isSameAs(page);
    }

    @Test
    void listCustomersWorksWithNoFiltersAtAll() {
        Pageable pageable = PageRequest.of(0, 30);
        Page<User> page = new PageImpl<>(List.of(userWithId("u1")));
        stubFindAll(page);

        assertThat(userService.listCustomers(false, null, null, null, pageable)).isSameAs(page);
    }

    @Test
    void listCustomersAppliesSearchAlongsideNeedsAttention() {
        Pageable pageable = PageRequest.of(0, 30);
        Page<User> page = new PageImpl<>(List.of(userWithId("u1")));
        stubFindAll(page);

        Page<User> result = userService.listCustomers(true, null, null, "ada", pageable);

        assertThat(result).isSameAs(page);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countNeedingAttentionCountsActiveUsersWithinTheNextDayOrWithAPendingExtensionRequest() {
        when(userRepository.count(any(Specification.class))).thenReturn(3L);

        assertThat(userService.countNeedingAttention()).isEqualTo(3L);
    }

    @Test
    void revokeTrialSetsTrialEndDateToNow() {
        User user = userWithId("u1");
        user.setTrialEndDate(Instant.now().plus(10, ChronoUnit.DAYS));
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User revoked = userService.revokeTrial("u1");

        assertThat(revoked.getTrialEndDate()).isBeforeOrEqualTo(Instant.now());
    }

    @Test
    void revokeTrialClearsAPendingExtensionRequest() {
        User user = userWithId("u1");
        user.setPendingExtensionNote("Need more time.");
        user.setPendingExtensionRequestedAt(Instant.now());
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User revoked = userService.revokeTrial("u1");

        assertThat(revoked.getPendingExtensionNote()).isNull();
        assertThat(revoked.getPendingExtensionRequestedAt()).isNull();
    }

    @Test
    void revokeTrialThrowsWhenUserMissing() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.revokeTrial("missing")).isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void extendTrialSetsTheNewEndDateWithinTheCap() {
        User user = userWithId("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Instant newEndDate = Instant.now().plus(10, ChronoUnit.DAYS);

        User extended = userService.extendTrial("u1", newEndDate);

        assertThat(extended.getTrialEndDate()).isEqualTo(newEndDate);
    }

    @Test
    void extendTrialThrowsWhenMoreThanFourteenDaysOut() {
        Instant tooFar = Instant.now().plus(15, ChronoUnit.DAYS);

        assertThatThrownBy(() -> userService.extendTrial("u1", tooFar))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("TRIAL_EXTENSION_TOO_FAR");
    }

    @Test
    void extendTrialThrowsWhenInThePast() {
        Instant past = Instant.now().minus(1, ChronoUnit.DAYS);

        assertThatThrownBy(() -> userService.extendTrial("u1", past))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("TRIAL_END_DATE_IN_PAST");
    }

    @Test
    void extendTrialClearsAPendingExtensionRequest() {
        User user = userWithId("u1");
        user.setPendingExtensionNote("Need more time.");
        user.setPendingExtensionRequestedAt(Instant.now());
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User extended = userService.extendTrial("u1", Instant.now().plus(10, ChronoUnit.DAYS));

        assertThat(extended.getPendingExtensionNote()).isNull();
        assertThat(extended.getPendingExtensionRequestedAt()).isNull();
    }

    @Test
    void requestTrialExtensionSetsTheNoteAndTimestamp() {
        User user = userWithId("u1");
        when(userRepository.findById("u1")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        User requested = userService.requestTrialExtension("u1", "Need more time.");

        assertThat(requested.getPendingExtensionNote()).isEqualTo("Need more time.");
        assertThat(requested.getPendingExtensionRequestedAt()).isCloseTo(Instant.now(), within(2, ChronoUnit.SECONDS));
    }

    @Test
    void requestTrialExtensionThrowsWhenUserMissing() {
        when(userRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.requestTrialExtension("missing", "Need more time."))
                .isInstanceOf(UserNotFoundException.class);
    }
}
