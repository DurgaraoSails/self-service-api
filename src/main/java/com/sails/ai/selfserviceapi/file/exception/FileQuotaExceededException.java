package com.sails.ai.selfserviceapi.file.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Either quota from the spec's Transport decision: the per-(user, POC) file count, or the
 * per-user total across every POC they have trialled. 409 rather than 413 — this is a conflict
 * with the caller's existing state (too much already stored), not a property of the request being
 * made right now.
 */
public class FileQuotaExceededException extends ApiException {

    private FileQuotaExceededException(String message) {
        super(HttpStatus.CONFLICT, "FILE_QUOTA_EXCEEDED", message);
    }

    public static FileQuotaExceededException perPocFileCount(long limit) {
        return new FileQuotaExceededException(
                "This POC already has the maximum of " + limit + " files. Delete one before uploading another.");
    }

    public static FileQuotaExceededException perUserTotalBytes(long limitBytes) {
        return new FileQuotaExceededException(
                "This upload would exceed your total storage limit of " + limitBytes
                        + " bytes across every POC. Delete a file before uploading another.");
    }
}
