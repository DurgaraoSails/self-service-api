package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class AssetNotFoundException extends ApiException {

    public AssetNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "Asset not found: " + id);
    }
}
