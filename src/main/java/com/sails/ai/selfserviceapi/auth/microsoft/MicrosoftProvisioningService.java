package com.sails.ai.selfserviceapi.auth.microsoft;

import com.sails.ai.selfserviceapi.auth.service.AuthService;
import com.sails.ai.selfserviceapi.auth.service.LoginResult;
import com.sails.ai.selfserviceapi.auth.service.RefreshTokenService;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.entity.AccountType;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import com.sails.ai.selfserviceapi.user.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MicrosoftProvisioningService {
    private final UserRepository users;
    private final RefreshTokenService refreshTokens;
    private final AuthService auth;
    private final JdbcTemplate jdbc;

    public MicrosoftProvisioningService(UserRepository users, RefreshTokenService refreshTokens, AuthService auth, JdbcTemplate jdbc) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.auth = auth;
        this.jdbc = jdbc;
    }

    @Transactional
    public LoginResult signIn(MicrosoftIdentityClient.Identity identity) {
        // A short database-only critical section serializes linking across Cloud Run instances.
        // No Microsoft requests run under this lock; PostgreSQL releases it at commit/rollback.
        jdbc.query("SELECT pg_advisory_xact_lock(735194820133)", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {});
        var matches = users.findByNormalizedEmail(identity.email());
        if (matches.size() > 1) throw conflict();
        User linked = users.findByMicrosoftTenantIdAndMicrosoftObjectId(identity.tenantId(), identity.objectId()).orElse(null);
        User emailMatch = matches.isEmpty() ? null : matches.getFirst();
        if (linked != null && emailMatch != null && !linked.getId().equals(emailMatch.getId())) throw conflict();
        User user = linked != null ? linked : emailMatch;
        if (user == null) {
            user = new User();
            user.setRoles(new ArrayList<>(List.of("USER")));
        }
        if (user.getStatus() == UserStatus.INACTIVE || user.getStatus() == UserStatus.SUSPENDED) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account cannot sign in.");
        }
        if (user.getMicrosoftObjectId() != null && (!identity.objectId().equals(user.getMicrosoftObjectId())
                || !identity.tenantId().equals(user.getMicrosoftTenantId()))) throw conflict();
        boolean transitioning = user.getAccountType() != AccountType.INTERNAL;
        if (transitioning && user.getId() != null) refreshTokens.revokeAllForUser(user.getId());
        var profile = identity.profile();
        user.setEmail(identity.email());
        user.setAccountType(AccountType.INTERNAL);
        user.setMicrosoftTenantId(identity.tenantId());
        user.setMicrosoftObjectId(identity.objectId());
        user.setCompanyName("Sails Software Solutions");
        user.setFirstName(bounded(firstNonBlank(profile.givenName(), profile.displayName(), "Employee")));
        user.setLastName(bounded(firstNonBlank(profile.surname(), "")));
        if (user.getDisplayName() == null) user.setDisplayName(bounded(profile.displayName()));
        user.setJobTitle(bounded(profile.jobTitle()));
        user.setCountry(bounded(profile.country()));
        user.setTrialStartDate(null);
        user.setTrialEndDate(null);
        user.setPendingExtensionNote(null);
        user.setPendingExtensionRequestedAt(null);
        user.setStatus(UserStatus.ACTIVE);
        if (user.getEmailVerifiedAt() == null) user.setEmailVerifiedAt(Instant.now());
        user.setLastLoginDate(Instant.now());
        users.saveAndFlush(user);
        return auth.issueTokensForAuthenticatedUser(user);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
    private static String bounded(String value) {
        if (value == null || value.isBlank()) return value == null ? null : "";
        String trimmed = value.trim();
        return trimmed.substring(0, Math.min(100, trimmed.length()));
    }
    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "MICROSOFT_ACCOUNT_CONFLICT",
                "Your Microsoft identity conflicts with an existing account. Contact your administrator.");
    }
}
