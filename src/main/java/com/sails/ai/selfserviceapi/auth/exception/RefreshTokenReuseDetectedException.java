package com.sails.ai.selfserviceapi.auth.exception;

/**
 * Thrown when an already-rotated/revoked refresh token is presented again — a signal
 * the token may have leaked. The entire token family for the user is revoked in response.
 */
public class RefreshTokenReuseDetectedException extends InvalidRefreshTokenException {

    public RefreshTokenReuseDetectedException(String message) {
        super(message);
    }
}
