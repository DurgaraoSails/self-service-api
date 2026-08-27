package com.sails.ai.selfserviceapi.auth.controller;

import com.sails.ai.selfserviceapi.auth.service.AuthService;
import com.sails.ai.selfserviceapi.auth.service.LoginResult;
import com.sails.ai.selfserviceapi.auth.service.OtpService;
import com.sails.ai.selfserviceapi.auth.service.RegistrationVerificationService;
import com.sails.ai.selfserviceapi.auth.service.TokenPair;
import com.sails.ai.selfserviceapi.generated.api.AuthApi;
import com.sails.ai.selfserviceapi.generated.model.IssueTokenRequest;
import com.sails.ai.selfserviceapi.generated.model.LoginResponse;
import com.sails.ai.selfserviceapi.generated.model.LogoutRequest;
import com.sails.ai.selfserviceapi.generated.model.OtpRequestRequest;
import com.sails.ai.selfserviceapi.generated.model.OtpRequestResponse;
import com.sails.ai.selfserviceapi.generated.model.OtpVerifyRequest;
import com.sails.ai.selfserviceapi.generated.model.RefreshTokenRequest;
import com.sails.ai.selfserviceapi.generated.model.RegisterRequest;
import com.sails.ai.selfserviceapi.generated.model.RegistrationVerifyRequest;
import com.sails.ai.selfserviceapi.generated.model.TokenResponse;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserResponseMapper;
import com.sails.ai.selfserviceapi.user.service.UserService;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController implements AuthApi {

    private final OtpService otpService;
    private final AuthService authService;
    private final UserService userService;
    private final RegistrationVerificationService registrationVerificationService;
    private final Environment environment;

    public AuthController(OtpService otpService, AuthService authService, UserService userService,
                           RegistrationVerificationService registrationVerificationService, Environment environment) {
        this.otpService = otpService;
        this.authService = authService;
        this.userService = userService;
        this.registrationVerificationService = registrationVerificationService;
        this.environment = environment;
    }

    @Override
    public ResponseEntity<OtpRequestResponse> register(RegisterRequest registerRequest) {
        userService.registerUser(
                registerRequest.getFirstName(),
                registerRequest.getLastName(),
                registerRequest.getCompanyName(),
                registerRequest.getJobTitle(),
                registerRequest.getCountry(),
                registerRequest.getEmail()
        );
        int expiresInSeconds = registrationVerificationService.requestVerification(registerRequest.getEmail());
        return ResponseEntity.status(HttpStatus.CREATED).body(new OtpRequestResponse()
                .message("Account created. Check your email for a link to verify your account.")
                .expiresInSeconds(expiresInSeconds));
    }

    @Override
    public ResponseEntity<LoginResponse> verifyRegistration(RegistrationVerifyRequest registrationVerifyRequest) {
        User user = registrationVerificationService.verify(registrationVerifyRequest.getToken());
        LoginResult loginResult = authService.issueTokensForVerifiedEmail(user.getEmail());
        return ResponseEntity.ok(toLoginResponse(loginResult));
    }

    @Override
    public ResponseEntity<OtpRequestResponse> requestOtp(OtpRequestRequest otpRequestRequest) {
        int expiresInSeconds = otpService.requestOtp(otpRequestRequest.getEmail());
        return ResponseEntity.ok(new OtpRequestResponse()
                .message("A verification code has been sent to your email.")
                .expiresInSeconds(expiresInSeconds));
    }

    @Override
    public ResponseEntity<OtpRequestResponse> resendOtp(OtpRequestRequest otpRequestRequest) {
        int expiresInSeconds = otpService.resendOtp(otpRequestRequest.getEmail());
        return ResponseEntity.ok(new OtpRequestResponse()
                .message("A new verification code has been sent to your email.")
                .expiresInSeconds(expiresInSeconds));
    }

    @Override
    public ResponseEntity<LoginResponse> verifyOtp(OtpVerifyRequest otpVerifyRequest) {
        // Verifying activates a PENDING_VERIFICATION user (registration) or is a no-op status
        // change for an already-ACTIVE one (login) — either way the user is ACTIVE by the time
        // issueTokensForVerifiedEmail looks it up.
        otpService.verifyOtp(otpVerifyRequest.getEmail(), otpVerifyRequest.getCode());
        LoginResult loginResult = authService.issueTokensForVerifiedEmail(otpVerifyRequest.getEmail());
        return ResponseEntity.ok(toLoginResponse(loginResult));
    }

    @Override
    public ResponseEntity<TokenResponse> issueTokens(IssueTokenRequest issueTokenRequest) {
        // Interim, pre-OTP login: mints real tokens for any already-registered, ACTIVE
        // email with no code verification. Disabled under the "prod" profile so it can
        // never act as a production auth bypass. Now that /auth/otp/verify issues real
        // tokens, this is mainly useful for quick manual testing.
        if (environment.matchesProfiles("prod")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        LoginResult loginResult = authService.issueTokensForVerifiedEmail(issueTokenRequest.getEmail());
        return ResponseEntity.ok(toResponse(loginResult.tokenPair()));
    }

    @Override
    public ResponseEntity<TokenResponse> refreshToken(RefreshTokenRequest refreshTokenRequest) {
        TokenPair tokenPair = authService.refresh(refreshTokenRequest.getRefreshToken());
        return ResponseEntity.ok(toResponse(tokenPair));
    }

    @Override
    public ResponseEntity<Void> logout(LogoutRequest logoutRequest) {
        authService.logout(logoutRequest.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    private TokenResponse toResponse(TokenPair tokenPair) {
        return new TokenResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                tokenPair.tokenType(),
                tokenPair.expiresIn()
        );
    }

    private LoginResponse toLoginResponse(LoginResult loginResult) {
        TokenPair tokenPair = loginResult.tokenPair();
        return new LoginResponse(
                tokenPair.accessToken(),
                tokenPair.refreshToken(),
                tokenPair.tokenType(),
                tokenPair.expiresIn(),
                UserResponseMapper.toResponse(loginResult.user()),
                loginResult.firstLogin()
        );
    }
}
