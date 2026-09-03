package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The storage backend failed. 502 rather than 500, matching {@code CloudBuildApiException}: the
 * request was fine and this service is fine — something it depends on is not.
 */
public class FileStorageException extends ApiException {

    public FileStorageException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "FILE_STORAGE_ERROR", message);
        initCause(cause);
    }
}
