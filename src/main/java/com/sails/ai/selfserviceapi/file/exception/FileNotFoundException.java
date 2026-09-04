package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * No live row for this id owned by the calling (user, POC) pair — whether because it never
 * existed, belongs to someone else, or was already deleted. All three collapse to the same 404 on
 * purpose: distinguishing "not yours" from "not found" would tell a caller that an id it has no
 * claim to is real, which is exactly what the POC-facing surface's claim-derived scoping exists to
 * avoid leaking.
 */
public class FileNotFoundException extends ApiException {

    public FileNotFoundException(Long fileId) {
        super(HttpStatus.NOT_FOUND, "FILE_NOT_FOUND", "File not found: " + fileId);
    }
}
