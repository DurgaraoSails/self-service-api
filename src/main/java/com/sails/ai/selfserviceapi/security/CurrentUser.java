package com.sails.ai.selfserviceapi.security;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public final class CurrentUser {

    private CurrentUser() {
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    public static boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + role));
    }

    public static boolean isInternal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return "INTERNAL".equals(jwt.getClaimAsString("accountType"));
    }

    /** Every Asset Hub API route requires this; call it first in every Asset Hub controller method. */
    public static void requireInternal() {
        if (!isInternal()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INTERNAL_ACCOUNT_REQUIRED",
                    "This action requires a verified internal account.");
        }
    }

    /** ASSET_REVIEWER is independent of ADMIN/SUPERADMIN; does not itself imply INTERNAL. */
    public static void requireAssetReviewer() {
        if (!hasRole("ASSET_REVIEWER")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ASSET_REVIEWER_REQUIRED",
                    "This action requires the ASSET_REVIEWER role.");
        }
    }

    public static String id() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return jwt.getSubject();
    }
}
