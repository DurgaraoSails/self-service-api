package com.sails.ai.selfserviceapi.asset.service;

import com.sails.ai.selfserviceapi.asset.entity.RoleChangeAudit;
import com.sails.ai.selfserviceapi.asset.repository.RoleChangeAuditRepository;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.model.EmployeePageResponse;
import com.sails.ai.selfserviceapi.generated.model.EmployeeResponse;
import com.sails.ai.selfserviceapi.generated.model.AccountType;
import com.sails.ai.selfserviceapi.generated.model.UpdateManagedRolesRequest;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.exception.UserNotFoundException;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeRoleService {

    private static final List<String> MANAGED_ROLES = List.of("ADMIN", "ASSET_REVIEWER");

    private final UserRepository userRepository;
    private final RoleChangeAuditRepository auditRepository;

    public EmployeeRoleService(UserRepository userRepository, RoleChangeAuditRepository auditRepository) {
        this.userRepository = userRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public EmployeePageResponse listEmployees(String search, int page, int size) {
        String normalized = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        Page<User> employees = userRepository.findAll((root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("accountType"), com.sails.ai.selfserviceapi.user.entity.AccountType.INTERNAL));
            predicates.add(cb.equal(root.get("status"), UserStatus.ACTIVE));
            if (!normalized.isEmpty()) {
                String pattern = "%" + normalized + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("firstName")), pattern),
                        cb.like(cb.lower(root.get("lastName")), pattern),
                        cb.like(cb.lower(root.get("email")), pattern)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        }, PageRequest.of(page, size, Sort.by("firstName").ascending()
                .and(Sort.by("lastName").ascending()).and(Sort.by("id").ascending())));

        return new EmployeePageResponse(
                employees.getContent().stream().map(EmployeeRoleService::toResponse).toList(),
                page, size, employees.getTotalElements(), employees.getTotalPages());
    }

    @Transactional
    public EmployeeResponse updateManagedRoles(String actorUserId, String targetUserId,
                                                 UpdateManagedRolesRequest request) {
        if (actorUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_SELF_MANAGEMENT_FORBIDDEN",
                    "You cannot change your own managed roles.");
        }
        User target = userRepository.lockById(targetUserId).orElseThrow(() -> new UserNotFoundException(targetUserId));
        if (target.getAccountType() != com.sails.ai.selfserviceapi.user.entity.AccountType.INTERNAL) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_TARGET_MUST_BE_INTERNAL",
                    "Managed roles can only be assigned to internal employees.");
        }
        if (target.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ROLE_TARGET_MUST_BE_ACTIVE",
                    "Managed roles can only be assigned to active employees.");
        }

        List<String> requested = request.getRoles().stream().map(UpdateManagedRolesRequest.RolesEnum::getValue).toList();
        if (new LinkedHashSet<>(requested).size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_MANAGED_ROLE",
                    "Each managed role may appear only once.");
        }
        if (requested.stream().anyMatch(role -> !MANAGED_ROLES.contains(role))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_MANAGED_ROLE",
                    "Only ADMIN and ASSET_REVIEWER can be managed through this endpoint.");
        }

        List<String> before = List.copyOf(target.getRoles() == null ? List.of() : target.getRoles());
        LinkedHashSet<String> after = new LinkedHashSet<>();
        after.add("USER");
        if (before.contains("SUPERADMIN")) {
            after.add("SUPERADMIN");
        }
        for (String role : MANAGED_ROLES) {
            if (requested.contains(role)) {
                after.add(role);
            }
        }
        target.setRoles(new ArrayList<>(after));
        userRepository.save(target);

        RoleChangeAudit audit = new RoleChangeAudit();
        audit.setActorUserId(actorUserId);
        audit.setTargetUserId(targetUserId);
        audit.setBeforeRoles(before);
        audit.setAfterRoles(new ArrayList<>(after));
        auditRepository.save(audit);

        return toResponse(target);
    }

    private static EmployeeResponse toResponse(User user) {
        List<String> roles = user.getRoles() == null ? List.of() : List.copyOf(user.getRoles());
        return new EmployeeResponse(user.getId(), user.getEmail(), user.getFirstName(), user.getLastName(),
                roles, AccountType.INTERNAL);
    }
}
