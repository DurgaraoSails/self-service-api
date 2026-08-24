package com.sails.ai.selfserviceapi.user.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class UserNotFoundException extends ApiException {

    public UserNotFoundException(String userId) {
        super(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User not found: " + userId);
    }
}
