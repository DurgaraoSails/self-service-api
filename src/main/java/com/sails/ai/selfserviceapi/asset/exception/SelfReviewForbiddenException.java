package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class SelfReviewForbiddenException extends ApiException {

    public SelfReviewForbiddenException() {
        super(HttpStatus.FORBIDDEN, "SELF_REVIEW_FORBIDDEN",
                "You cannot review an asset you submitted or a revision you authored.");
    }
}
