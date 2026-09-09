package com.sails.ai.selfserviceapi.auth.microsoft;

import java.net.URI;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

@ConfigurationProperties("microsoft-sso")
public record MicrosoftSsoProperties(boolean enabled, String tenantId, String clientId,
                                     String clientSecret, String redirectUri) {
    public void requireEnabled() {
        if (!enabled) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MICROSOFT_SSO_DISABLED",
                "Microsoft sign-in is not available yet. Please contact your administrator.");
        try {
            UUID.fromString(tenantId);
            UUID.fromString(clientId);
            URI uri = URI.create(redirectUri);
            if (clientSecret == null || clientSecret.isBlank() || uri.getHost() == null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null
                    || !("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && "localhost".equals(uri.getHost())))) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MICROSOFT_SSO_CONFIGURATION",
                    "Microsoft sign-in configuration is incomplete. Please contact your administrator.");
        }
    }

    public String authority() { return "https://login.microsoftonline.com/" + tenantId; }

    @Override public String toString() { return "MicrosoftSsoProperties[enabled=" + enabled + ", credentials=REDACTED]"; }
}
