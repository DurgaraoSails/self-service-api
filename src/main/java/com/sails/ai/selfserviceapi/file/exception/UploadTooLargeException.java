package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A single file exceeds {@code files.max-file-size}. In practice Spring's own multipart limit
 * (derived from the same property — see MultipartUploadConfig) rejects an oversized upload before
 * a request reaches the controller, so this is defense in depth and what a direct
 * FileService.upload call sees, rather than the primary path a browser upload hits.
 */
public class UploadTooLargeException extends ApiException {

    public UploadTooLargeException(long actualBytes, long maxBytes) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                "File is " + actualBytes + " bytes; the limit is " + maxBytes + " bytes.");
    }
}
