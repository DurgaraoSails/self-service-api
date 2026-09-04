package com.sails.ai.selfserviceapi.common.exception;

import com.sails.ai.selfserviceapi.generated.model.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(ex.getCode(), ex.getMessage())
                .timestamp(OffsetDateTime.now())
                .path(request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Validation failed");
        ErrorResponse body = new ErrorResponse("VALIDATION_ERROR", message)
                .timestamp(OffsetDateTime.now())
                .path(request.getRequestURI());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Spring's own multipart limit (see MultipartUploadConfig) rejects an oversized upload before
     * FileService's own check ever runs, at a layer with no ApiException to catch. Mapped here so
     * the response still matches this API's ErrorResponse shape instead of Spring's default body.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse("FILE_TOO_LARGE", "Upload exceeds the maximum allowed size.")
                .timestamp(OffsetDateTime.now())
                .path(request.getRequestURI());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body);
    }
}
