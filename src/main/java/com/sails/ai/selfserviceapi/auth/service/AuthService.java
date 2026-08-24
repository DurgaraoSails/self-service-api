package com.sails.ai.selfserviceapi.auth.service;

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
        User user = userService.getActiveByEmail(email);
        return new LoginResult(user, buildPair(user));
    }

    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        RefreshToken oldToken = refreshTokenService.validateAndConsume(rawRefreshToken);
        User user = userService.getById(oldToken.getUserId());

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
}
