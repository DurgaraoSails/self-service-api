package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class AssetRevisionStaleException extends ApiException {

    public AssetRevisionStaleException() {
        super(HttpStatus.CONFLICT, "ASSET_REVISION_STALE",
                "Expected version does not match the current version. Reload and try again.");
    }
}
