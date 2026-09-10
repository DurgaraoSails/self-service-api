package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AssetRevisionNotFoundException extends ApiException {

    public AssetRevisionNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "ASSET_REVISION_NOT_FOUND", "Asset revision not found: " + id);
    }
}
