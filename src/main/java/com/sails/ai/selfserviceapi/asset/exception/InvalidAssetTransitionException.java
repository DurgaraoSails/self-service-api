package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidAssetTransitionException extends ApiException {

    public InvalidAssetTransitionException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_ASSET_TRANSITION", message);
    }
}
