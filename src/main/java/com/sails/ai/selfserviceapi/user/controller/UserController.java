package com.sails.ai.selfserviceapi.user.controller;

import com.sails.ai.selfserviceapi.generated.api.UserApi;
import com.sails.ai.selfserviceapi.generated.model.CustomerPageResponse;
import com.sails.ai.selfserviceapi.generated.model.CustomerResponse;
import com.sails.ai.selfserviceapi.generated.model.ExtendTrialRequest;
import com.sails.ai.selfserviceapi.generated.model.TrialAlertsCountResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdateUserRequest;
import com.sails.ai.selfserviceapi.generated.model.UserResponse;
import com.sails.ai.selfserviceapi.user.entity.ThemeMode;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.CustomerResponseMapper;
import com.sails.ai.selfserviceapi.user.service.UserResponseMapper;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserController implements UserApi {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Override
    public ResponseEntity<UserResponse> getCurrentUser() {
        User user = userService.getById(currentUserId());
        return ResponseEntity.ok(UserResponseMapper.toResponse(user));
    }

    @Override
    public ResponseEntity<UserResponse> updateCurrentUser(UpdateUserRequest updateUserRequest) {
        User user = userService.updateProfile(currentUserId(), updateUserRequest.getDisplayName(), toEntityTheme(updateUserRequest.getTheme()));
        return ResponseEntity.ok(UserResponseMapper.toResponse(user));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CustomerPageResponse> getUsers(OffsetDateTime registeredFrom, OffsetDateTime registeredTo,
                                                          Boolean needsAttention, Integer page, Integer size) {
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<User> users = userService.listCustomers(
                Boolean.TRUE.equals(needsAttention),
                registeredFrom != null ? registeredFrom.toInstant() : null,
                registeredTo != null ? registeredTo.toInstant() : null,
                pageRequest
        );
        return ResponseEntity.ok(CustomerResponseMapper.toPageResponse(users));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TrialAlertsCountResponse> getTrialAlertsCount() {
        return ResponseEntity.ok(new TrialAlertsCountResponse((int) userService.countNeedingAttention()));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CustomerResponse> revokeTrial(String id) {
        return ResponseEntity.ok(CustomerResponseMapper.toResponse(userService.revokeTrial(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CustomerResponse> extendTrial(String id, ExtendTrialRequest extendTrialRequest) {
        User user = userService.extendTrial(id, extendTrialRequest.getTrialEndDate().toInstant());
        return ResponseEntity.ok(CustomerResponseMapper.toResponse(user));
    }

    private String currentUserId() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return jwt.getSubject();
    }

    private ThemeMode toEntityTheme(com.sails.ai.selfserviceapi.generated.model.ThemeMode theme) {
        return theme == null ? null : ThemeMode.valueOf(theme.name());
    }
}
