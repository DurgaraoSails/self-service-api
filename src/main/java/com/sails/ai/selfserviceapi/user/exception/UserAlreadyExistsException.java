package com.sails.ai.selfserviceapi.user.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class UserAlreadyExistsException extends ApiException {

    public UserAlreadyExistsException() {
        super(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "An account with this email already exists.");
    }
}
