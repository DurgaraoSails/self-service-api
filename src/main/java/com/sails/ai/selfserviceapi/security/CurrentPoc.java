package com.sails.ai.selfserviceapi.security;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads the (user, POC) scope out of a POC-scoped token's claims. Mirrors {@link CurrentUser} but
 * is deliberately its own class rather than an addition to it: {@code pocId} only exists on a
 * token that passed {@link RequirePocAudienceValidator}, and every caller of this class already
 * sits behind that check by construction, on the POC-files filter chain.
 */
public final class CurrentPoc {

    private CurrentPoc() {
    }

    /** The numeric id, not the slug — what {@code user_files} and object paths are keyed on. */
    public static Long id() {
        Jwt jwt = jwt();
        Object claim = jwt.getClaim("pocId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Token has no pocId claim — not a POC-scoped token");
    }

    private static Jwt jwt() {
        return (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
