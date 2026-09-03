package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class UnsupportedFileTypeException extends ApiException {

    public static UnsupportedFileTypeException notAllowed(String declaredContentType) {
        return new UnsupportedFileTypeException(
                "File type not accepted: " + declaredContentType);
    }

    /**
     * The declared type is on the allowlist but the bytes are something else. Worth a distinct
     * message from a plain rejection: it is the case where a caller is either confused or lying.
     */
    public static UnsupportedFileTypeException contentMismatch(String declaredContentType) {
        return new UnsupportedFileTypeException(
                "File contents do not match the declared type: " + declaredContentType);
    }

    private UnsupportedFileTypeException(String message) {
        super(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", message);
    }
}
