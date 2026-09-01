package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidWebhookSecretException extends ApiException {

    public InvalidWebhookSecretException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SECRET", "Invalid or missing X-Pipeline-Webhook-Secret.");
    }
}
