package com.sails.ai.selfserviceapi.asset.ai;

/**
 * Thrown by an {@link AssetAiProvider} on timeout, transport failure, or a response that does not
 * validate against the suggestion schema. The message is for logs only; {@link #errorCode()} is
 * the safe, non-sensitive classification stored on {@code asset_ai_suggestions.error_code} and
 * returned to callers — never the raw provider error text.
 */
public class AssetAiProviderException extends RuntimeException {

    private final String errorCode;

    public AssetAiProviderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public AssetAiProviderException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
