package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentUserTest {

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void isInternalTrueOnlyForTheInternalAccountTypeClaim() {
        authenticateAs(jwt(Map.of("sub", "user-1", "accountType", "INTERNAL")), List.of());
        assertThat(CurrentUser.isInternal()).isTrue();

        authenticateAs(jwt(Map.of("sub", "user-1", "accountType", "EXTERNAL")), List.of());
        assertThat(CurrentUser.isInternal()).isFalse();
    }

    @Test
    void isInternalFalseWhenUnauthenticated() {
        assertThat(CurrentUser.isInternal()).isFalse();
    }

    @Test
    void requireInternalThrowsForbiddenWhenNotInternal() {
        authenticateAs(jwt(Map.of("sub", "user-1", "accountType", "EXTERNAL")), List.of());

        assertThatThrownBy(CurrentUser::requireInternal)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiException.getCode()).isEqualTo("INTERNAL_ACCOUNT_REQUIRED");
                });
    }

    @Test
    void requireInternalPassesWhenInternal() {
        authenticateAs(jwt(Map.of("sub", "user-1", "accountType", "INTERNAL")), List.of());
        CurrentUser.requireInternal();
    }

    @Test
    void hasRoleReadsGrantedAuthoritiesNotJwtClaims() {
        authenticateAs(jwt(Map.of("sub", "user-1")), List.of(new SimpleGrantedAuthority("ROLE_ASSET_REVIEWER")));

        assertThat(CurrentUser.hasRole("ASSET_REVIEWER")).isTrue();
        assertThat(CurrentUser.hasRole("SUPERADMIN")).isFalse();
    }

    @Test
    void hasRoleFalseWhenUnauthenticated() {
        assertThat(CurrentUser.hasRole("ASSET_REVIEWER")).isFalse();
    }

    @Test
    void requireAssetReviewerThrowsForbiddenWithoutTheRole() {
        authenticateAs(jwt(Map.of("sub", "user-1")), List.of());

        assertThatThrownBy(CurrentUser::requireAssetReviewer)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiException.getCode()).isEqualTo("ASSET_REVIEWER_REQUIRED");
                });
    }

    @Test
    void requireAssetReviewerIgnoresAdminAndSuperadminAuthorities() {
        authenticateAs(jwt(Map.of("sub", "user-1")),
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_SUPERADMIN")));

        assertThatThrownBy(CurrentUser::requireAssetReviewer).isInstanceOf(ApiException.class);
    }

    @Test
    void requireSuperAdminThrowsForbiddenWithoutTheRole() {
        authenticateAs(jwt(Map.of("sub", "user-1")), List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));

        assertThatThrownBy(CurrentUser::requireSuperAdmin)
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException apiException = (ApiException) e;
                    assertThat(apiException.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(apiException.getCode()).isEqualTo("SUPERADMIN_REQUIRED");
                });
    }

    @Test
    void requireSuperAdminPassesWithTheRole() {
        authenticateAs(jwt(Map.of("sub", "user-1")), List.of(new SimpleGrantedAuthority("ROLE_SUPERADMIN")));
        CurrentUser.requireSuperAdmin();
    }

    @Test
    void isAdminReadsTheRoleAdminAuthority() {
        authenticateAs(jwt(Map.of("sub", "user-1")), List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        assertThat(CurrentUser.isAdmin()).isTrue();

        authenticateAs(jwt(Map.of("sub", "user-1")), List.of());
        assertThat(CurrentUser.isAdmin()).isFalse();
    }

    @Test
    void idReturnsTheJwtSubject() {
        authenticateAs(jwt(Map.of("sub", "user-42")), List.of());
        assertThat(CurrentUser.id()).isEqualTo("user-42");
    }

    private static void authenticateAs(Jwt jwt, List<? extends GrantedAuthority> authorities) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"), claims);
    }
}
