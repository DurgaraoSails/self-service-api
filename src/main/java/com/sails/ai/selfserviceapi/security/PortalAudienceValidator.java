package com.sails.ai.selfserviceapi.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects a POC-scoped token on the portal's endpoints.
 *
 * <p>Both token classes are signed by the same key and carry the same issuer, so without this a
 * POC-scoped token would satisfy {@code anyRequest().authenticated()} and reach {@code /users/me},
 * the admin endpoints, and everything else — while being, by design, resident in JavaScript on a
 * POC's own origin. The audience is the only thing that separates them, so it is checked at
 * decode time rather than as an authorization rule: the token fails to validate at all, instead of
 * authenticating successfully and then being denied.
 */
public class PortalAudienceValidator implements OAuth2TokenValidator<Jwt> {

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (PocAudience.isPocToken(jwt.getAudience())) {
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "A POC-scoped token cannot be used against this API's portal endpoints",
                    null));
        }
        return OAuth2TokenValidatorResult.success();
    }
}
