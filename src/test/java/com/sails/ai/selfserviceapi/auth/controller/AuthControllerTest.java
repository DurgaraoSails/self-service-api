package com.sails.ai.selfserviceapi.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.auth.config.DevTokenIssuanceProperties;
import com.sails.ai.selfserviceapi.auth.service.AuthService;
import com.sails.ai.selfserviceapi.auth.service.LoginResult;
import com.sails.ai.selfserviceapi.auth.service.OtpService;
import com.sails.ai.selfserviceapi.auth.service.RegistrationVerificationService;
import com.sails.ai.selfserviceapi.auth.service.TokenPair;
import com.sails.ai.selfserviceapi.generated.model.IssueTokenRequest;
import com.sails.ai.selfserviceapi.generated.model.TokenResponse;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code POST /auth/tokens} is a zero-verification token mint used only for manual testing — see
 * {@link DevTokenIssuanceProperties}'s own doc comment for why this must fail closed (404) by
 * default rather than relying on "not the prod profile".
 */
class AuthControllerTest {

    private final OtpService otpService = mock(OtpService.class);
    private final AuthService authService = mock(AuthService.class);
    private final UserService userService = mock(UserService.class);
    private final RegistrationVerificationService registrationVerificationService =
            mock(RegistrationVerificationService.class);

    private AuthController controllerWith(boolean devTokenIssuanceEnabled) {
        return new AuthController(otpService, authService, userService, registrationVerificationService,
                new DevTokenIssuanceProperties(devTokenIssuanceEnabled));
    }

    @Test
    void returns404WhenDevTokenIssuanceIsDisabled() {
        AuthController controller = controllerWith(false);
        IssueTokenRequest request = new IssueTokenRequest().email("dev@example.com");

        assertThatThrownBy(() -> controller.issueTokens(request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void issuesTokensWhenDevTokenIssuanceIsExplicitlyEnabled() {
        AuthController controller = controllerWith(true);
        IssueTokenRequest request = new IssueTokenRequest().email("dev@example.com");

        User user = mock(User.class);
        TokenPair tokenPair = new TokenPair("access-token", "refresh-token", "Bearer", 1800L);
        when(authService.issueTokensForVerifiedEmail("dev@example.com"))
                .thenReturn(new LoginResult(user, tokenPair, false));

        ResponseEntity<TokenResponse> response = controller.issueTokens(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getAccessToken()).isEqualTo("access-token");
    }
}
