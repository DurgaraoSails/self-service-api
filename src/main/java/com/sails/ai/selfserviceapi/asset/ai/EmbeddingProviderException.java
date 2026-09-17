package com.sails.ai.selfserviceapi.asset.ai;

/**
 * Thrown by an {@link EmbeddingProvider} on timeout, transport failure, or a response that does not
 * match the expected dimension. The message is for logs only; {@link #errorCode()} is the safe,
 * non-sensitive classification — never the raw provider error text.
 */
public class EmbeddingProviderException extends RuntimeException {

    private final String errorCode;

    public EmbeddingProviderException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmbeddingProviderException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
