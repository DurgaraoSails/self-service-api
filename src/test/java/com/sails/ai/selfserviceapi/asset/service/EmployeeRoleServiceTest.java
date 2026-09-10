package com.sails.ai.selfserviceapi.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.asset.entity.RoleChangeAudit;
import com.sails.ai.selfserviceapi.asset.repository.RoleChangeAuditRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.EmployeeResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdateManagedRolesRequest;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class EmployeeRoleServiceTest {

    private UserRepository userRepository;
    private RoleChangeAuditRepository auditRepository;
    private EmployeeRoleService service;

    @BeforeEach
    void setUp() {
        userRepository = Mockito.mock(UserRepository.class);
        auditRepository = Mockito.mock(RoleChangeAuditRepository.class);
        service = new EmployeeRoleService(userRepository, auditRepository);
    }

    @Test
    void replacesManagedRolesWhilePreservingBaselineAndSuperadminAndAudits() {
        User target = employee("target", List.of("USER", "SUPERADMIN", "ADMIN"));
        when(userRepository.lockById("target")).thenReturn(Optional.of(target));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateManagedRolesRequest request = new UpdateManagedRolesRequest(List.of(
                UpdateManagedRolesRequest.RolesEnum.ASSET_REVIEWER));
        EmployeeResponse response = service.updateManagedRoles("actor", "target", request);

        assertThat(response.getRoles()).containsExactly("USER", "SUPERADMIN", "ASSET_REVIEWER");
        ArgumentCaptor<RoleChangeAudit> audit = ArgumentCaptor.forClass(RoleChangeAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getBeforeRoles()).containsExactly("USER", "SUPERADMIN", "ADMIN");
        assertThat(audit.getValue().getAfterRoles()).containsExactly("USER", "SUPERADMIN", "ASSET_REVIEWER");
        assertThat(audit.getValue().getActorUserId()).isEqualTo("actor");
    }

    @Test
    void blocksSelfManagement() {
        UpdateManagedRolesRequest request = new UpdateManagedRolesRequest(List.of());

        assertThatThrownBy(() -> service.updateManagedRoles("same", "same", request))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("own managed roles");
    }

    @Test
    void blocksExternalTargets() {
        User target = employee("external", List.of("USER"));
        target.setAccountType(AccountType.EXTERNAL);
        when(userRepository.lockById("external")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateManagedRoles("actor", "external",
                new UpdateManagedRolesRequest(List.of(UpdateManagedRolesRequest.RolesEnum.ADMIN))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("internal employees");
    }

    @Test
    void blocksInactiveTargets() {
        User target = employee("inactive", List.of("USER"));
        target.setStatus(com.sails.ai.selfserviceapi.user.entity.UserStatus.INACTIVE);
        when(userRepository.lockById("inactive")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateManagedRoles("actor", "inactive",
                new UpdateManagedRolesRequest(List.of(UpdateManagedRolesRequest.RolesEnum.ADMIN))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("active employees");
    }

    @Test
    void blocksDuplicateManagedRoles() {
        User target = employee("target", List.of("USER"));
        when(userRepository.lockById("target")).thenReturn(Optional.of(target));

        assertThatThrownBy(() -> service.updateManagedRoles("actor", "target",
                new UpdateManagedRolesRequest(List.of(
                        UpdateManagedRolesRequest.RolesEnum.ADMIN,
                        UpdateManagedRolesRequest.RolesEnum.ADMIN))))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("only once");
    }

    private static User employee(String id, List<String> roles) {
        User user = new User();
        user.setId(id);
        user.setEmail(id + "@sailssoftware.com");
        user.setFirstName("Test");
        user.setLastName("Employee");
        user.setAccountType(AccountType.INTERNAL);
        user.setStatus(com.sails.ai.selfserviceapi.user.entity.UserStatus.ACTIVE);
        user.setRoles(new java.util.ArrayList<>(roles));
        return user;
    }
}
