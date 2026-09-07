package com.sails.ai.selfserviceapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gates {@code POST /auth/tokens}, the zero-verification token-mint endpoint used for manual
 * testing now that {@code /auth/otp/verify} issues real tokens.
 *
 * <p>Deliberately a positive opt-in rather than the "disabled under the prod profile" check this
 * replaces: that check fails <em>open</em> (the endpoint stays live) if a deploy target's Spring
 * profile is ever misnamed, omitted, or simply not called "prod" — an auth bypass no one would
 * notice until it mattered. Defaulting {@code enabled} to {@code false} here means the same
 * mistake fails <em>closed</em> instead.
 */
@ConfigurationProperties(prefix = "auth.dev-token-issuance")
public record DevTokenIssuanceProperties(boolean enabled) {
}
