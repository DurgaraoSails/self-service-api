package com.sails.ai.selfserviceapi.auth.microsoft;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.sails.ai.selfserviceapi.auth.service.AuthService;
import com.sails.ai.selfserviceapi.auth.service.RefreshTokenService;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class MicrosoftProvisioningServiceTest {
    private UserRepository users;
    private RefreshTokenService refresh;
    private AuthService auth;
    private MicrosoftProvisioningService service;
    private final MicrosoftIdentityClient.Identity identity = new MicrosoftIdentityClient.Identity("tenant", "object", "jane@sailssoftware.com",
            new MicrosoftIdentityClient.Profile("object", "Member", "jane@sailssoftware.com", null, null, null, "Jane Doe", null, null));

    @BeforeEach void setup() {
        users = mock(UserRepository.class);
        refresh = mock(RefreshTokenService.class);
        auth = mock(AuthService.class);
        service = new MicrosoftProvisioningService(users, refresh, auth, mock(JdbcTemplate.class));
    }
    private User savedUser() {
        var captor = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(captor.capture());
        verify(auth).issueTokensForAuthenticatedUser(captor.getValue());
        return captor.getValue();
    }
    @Test void newEmployeeIsActiveWithoutTrialOrAdminAndHasNameFallback() {
        service.signIn(identity);
        User user = savedUser();
        assertThat(user.getAccountType()).isEqualTo(AccountType.INTERNAL);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getRoles()).containsExactly("USER");
        assertThat(user.getFirstName()).isEqualTo("Jane Doe");
        assertThat(user.getLastName()).isEmpty();
        assertThat(user.getCountry()).isNull();
        assertThat(user.getTrialEndDate()).isNull();
        assertThat(user.getMicrosoftObjectId()).isEqualTo("object");
        verifyNoInteractions(refresh);
    }
    @Test void linksExistingAdminPreservingHistoryAndPreference() {
        User existing = new User(); existing.setId("existing-id");
        existing.setRoles(List.of("USER", "ADMIN", "SUPERADMIN"));
        existing.setDisplayName("Preferred name");
        existing.setTrialEndDate(Instant.now().minusSeconds(3600));
        existing.setPendingExtensionNote("Old request");
        when(users.findByNormalizedEmail(identity.email())).thenReturn(List.of(existing));
        service.signIn(identity);
        assertThat(savedUser()).isSameAs(existing);
        assertThat(existing.getId()).isEqualTo("existing-id");
        assertThat(existing.getRoles()).contains("ADMIN", "SUPERADMIN");
        assertThat(existing.getDisplayName()).isEqualTo("Preferred name");
        assertThat(existing.getTrialEndDate()).isNull();
        assertThat(existing.getPendingExtensionNote()).isNull();
        verify(refresh).revokeAllForUser("existing-id");
    }
    @Test void repeatedSignInUsesObjectIdentityAfterEmailChange() {
        User linked = new User(); linked.setId("existing-id"); linked.setAccountType(AccountType.INTERNAL);
        linked.setMicrosoftObjectId("object"); linked.setMicrosoftTenantId("tenant");
        when(users.findByMicrosoftTenantIdAndMicrosoftObjectId("tenant", "object")).thenReturn(Optional.of(linked));
        service.signIn(identity);
        assertThat(savedUser()).isSameAs(linked);
        assertThat(linked.getEmail()).isEqualTo(identity.email());
        verifyNoInteractions(refresh);
    }
    @Test void refusesAmbiguousEmailMatches() {
        when(users.findByNormalizedEmail(identity.email())).thenReturn(List.of(new User(), new User()));
        assertThatThrownBy(() -> service.signIn(identity)).isInstanceOf(ApiException.class);
        verify(users, never()).saveAndFlush(any()); verifyNoInteractions(auth);
    }
    @Test void refusesIdentityAlreadyLinkedToDifferentObject() {
        User user = new User(); user.setMicrosoftTenantId("tenant"); user.setMicrosoftObjectId("other");
        when(users.findByNormalizedEmail(identity.email())).thenReturn(List.of(user));
        assertThatThrownBy(() -> service.signIn(identity)).isInstanceOf(ApiException.class);
        verifyNoInteractions(auth, refresh);
    }
    @Test void refusesEmailCollisionWithAnotherLinkedUser() {
        User linked = new User(); linked.setId("one");
        User collision = new User(); collision.setId("two");
        when(users.findByNormalizedEmail(identity.email())).thenReturn(List.of(collision));
        when(users.findByMicrosoftTenantIdAndMicrosoftObjectId("tenant", "object")).thenReturn(Optional.of(linked));
        assertThatThrownBy(() -> service.signIn(identity)).isInstanceOf(ApiException.class);
    }
    @Test void neverReactivatesLocallySuspendedOrInactiveAccounts() {
        for (var status : List.of(UserStatus.SUSPENDED, UserStatus.INACTIVE)) {
            User user = new User(); user.setStatus(status);
            when(users.findByNormalizedEmail(identity.email())).thenReturn(List.of(user));
            assertThatThrownBy(() -> service.signIn(identity)).isInstanceOf(ApiException.class);
        }
        verifyNoInteractions(auth, refresh);
    }
}
