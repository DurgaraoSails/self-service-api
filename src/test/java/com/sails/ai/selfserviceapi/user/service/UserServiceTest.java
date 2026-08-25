package com.sails.ai.selfserviceapi.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.user.config.TrialProperties;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.exception.UserAlreadyExistsException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

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
}
