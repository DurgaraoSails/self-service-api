package com.sails.ai.selfserviceapi.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The mirror image of {@link PortalAudienceValidator}: rejects a user's own access token on the
 * POC-facing surface. Both classes of token are signed by the same key and share every other
 * claim shape, so without this a portal access token would authenticate against
 * {@code /poc-files} — and the POC-facing endpoints take no user or POC parameter anywhere,
 * relying entirely on the token's claims for scope, so the wrong token class here would not be
 * rejected by anything downstream.
 */
public class RequirePocAudienceValidator implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (!PocAudience.isPocToken(jwt.getAudience())) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "A POC-scoped token is required for this endpoint",
                    null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
