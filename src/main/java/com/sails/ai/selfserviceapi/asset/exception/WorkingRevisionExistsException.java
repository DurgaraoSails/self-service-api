package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class WorkingRevisionExistsException extends ApiException {

    public WorkingRevisionExistsException() {
        super(HttpStatus.CONFLICT, "WORKING_REVISION_EXISTS",
                "An editable or pending working revision already exists for this asset.");
    }
}
