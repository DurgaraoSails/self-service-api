package com.sails.ai.selfserviceapi.auth.service;

import com.sails.ai.selfserviceapi.auth.microsoft.EmployeeAccess;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import org.springframework.http.HttpStatus;

import com.sails.ai.selfserviceapi.auth.entity.RefreshToken;
import com.sails.ai.selfserviceapi.security.JwtService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    public AuthService(UserService userService, JwtService jwtService, RefreshTokenService refreshTokenService) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    /**
     * Assumes identity has already been verified by the caller (interim: email-only login;
     * OTP: after successful code verification). Requires an existing, ACTIVE user — account
     * creation happens through registration, not through a login/verification step, so this
     * enforces the same precondition OtpService.verifyOtp already does.
     */
    @Transactional
    public LoginResult issueTokensForVerifiedEmail(String email) {
        EmployeeAccess.requireExternal(email);
        User user = userService.getActiveByEmail(email);
        EmployeeAccess.requireExternal(user);
        return issueTokensForAuthenticatedUser(user);
    }

    /** Internal seam; callers must authenticate the identity before invoking this method. */
    @Transactional
    public LoginResult issueTokensForAuthenticatedUser(User user) {
        requireActive(user);
        boolean firstLogin = user.isFirstLogin();
        userService.clearFirstLoginFlag(user);
        return new LoginResult(user, buildPair(user), firstLogin);
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken oldToken = refreshTokenService.validateAndConsume(rawRefreshToken);
        User user = userService.getById(oldToken.getUserId());
        requireActive(user);

        RefreshTokenService.Issued issued = refreshTokenService.issue(user);
        refreshTokenService.rotate(oldToken, issued.entity());

        return new TokenPair(
                jwtService.issueAccessToken(user),
                issued.rawToken(),
                "Bearer",
                jwtService.accessTokenTtlSeconds()
        );
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenService.revokeByRawToken(rawRefreshToken);
    }

    private TokenPair buildPair(User user) {
        RefreshTokenService.Issued issued = refreshTokenService.issue(user);
        return new TokenPair(
                jwtService.issueAccessToken(user),
                issued.rawToken(),
                "Bearer",
                jwtService.accessTokenTtlSeconds()
        );
    }

    private void requireActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_INACTIVE", "This account cannot sign in.");
        }
    }
}
