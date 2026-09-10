package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class AssetAiUnavailableException extends ApiException {

    public AssetAiUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "ASSET_AI_UNAVAILABLE",
                "The suggestion provider is disabled or unavailable. Manual work remains usable.");
    }
}
