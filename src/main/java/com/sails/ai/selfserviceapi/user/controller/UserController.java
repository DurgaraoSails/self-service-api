package com.sails.ai.selfserviceapi.user.controller;

import com.sails.ai.selfserviceapi.generated.api.UserApi;
import com.sails.ai.selfserviceapi.generated.model.UpdateUserRequest;
import com.sails.ai.selfserviceapi.generated.model.UserResponse;
import com.sails.ai.selfserviceapi.user.entity.ThemeMode;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserResponseMapper;
import com.sails.ai.selfserviceapi.user.service.UserService;
import org.springframework.http.ResponseEntity;
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

    private String currentUserId() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return jwt.getSubject();
    }

    private ThemeMode toEntityTheme(com.sails.ai.selfserviceapi.generated.model.ThemeMode theme) {
        return theme == null ? null : ThemeMode.valueOf(theme.name());
    }
}
