package com.sails.ai.selfserviceapi.user.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidEmailDomainException extends ApiException {

    public InvalidEmailDomainException() {
        super(HttpStatus.BAD_REQUEST, "INVALID_EMAIL_DOMAIN", "This email domain can't receive mail. Check for a typo and try again.");
    }
}
