package com.sails.ai.selfserviceapi.user.controller;

import com.sails.ai.selfserviceapi.generated.api.UserApi;
import com.sails.ai.selfserviceapi.generated.model.UpdateUserRequest;
import com.sails.ai.selfserviceapi.generated.model.UserResponse;
import org.springframework.http.ResponseEntity;

public class UserController implements UserApi {
    @Override
    public ResponseEntity<UserResponse> getCurrentUser() {
        return null;
    }

    @Override
    public ResponseEntity<UserResponse> updateCurrentUser(UpdateUserRequest updateUserRequest) {
        return null;
    }
}
