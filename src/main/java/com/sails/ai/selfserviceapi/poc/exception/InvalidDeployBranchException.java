package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The deployBranch on a create/update request is not a name git would accept as a ref.
 *
 * <p>Checked here rather than left to the deploy: the branch is stored now and used minutes or days
 * later by an async pipeline, where the only way to report it is a failed deployment row. The admin
 * who typed it is standing right here.
 */
public class InvalidDeployBranchException extends ApiException {

    public InvalidDeployBranchException(String message) {
        super(HttpStatus.BAD_REQUEST, "INVALID_DEPLOY_BRANCH", message);
    }
}
