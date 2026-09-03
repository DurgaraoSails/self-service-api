package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A row exists but its object does not. Purge deletes objects before rows precisely so this is
 * the inconsistency that can happen rather than the reverse (an object no row can name, which
 * the platform could never purge). It is therefore expected, survivable, and a 404 to the caller.
 */
public class FileContentNotFoundException extends ApiException {

    public FileContentNotFoundException(String objectName) {
        super(HttpStatus.NOT_FOUND, "FILE_CONTENT_NOT_FOUND", "File content is no longer available: " + objectName);
    }
}
