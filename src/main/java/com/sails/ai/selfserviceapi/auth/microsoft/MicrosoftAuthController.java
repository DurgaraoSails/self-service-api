package com.sails.ai.selfserviceapi.auth.microsoft;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.api.MicrosoftAuthApi;
import com.sails.ai.selfserviceapi.generated.model.LoginResponse;
import com.sails.ai.selfserviceapi.generated.model.MicrosoftStartRequest;
import com.sails.ai.selfserviceapi.generated.model.MicrosoftStartResponse;
import com.sails.ai.selfserviceapi.generated.model.MicrosoftCompleteRequest;
import com.sails.ai.selfserviceapi.user.service.UserResponseMapper;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MicrosoftAuthController implements MicrosoftAuthApi {
    private final MicrosoftSsoProperties properties;
    private final MicrosoftAuthorizationStore authorizations;
    private final MicrosoftIdentityClient microsoft;
    private final MicrosoftProvisioningService provisioning;

    public MicrosoftAuthController(MicrosoftSsoProperties properties, MicrosoftAuthorizationStore authorizations,
                                   MicrosoftIdentityClient microsoft, MicrosoftProvisioningService provisioning) {
        this.properties = properties;
        this.authorizations = authorizations;
        this.microsoft = microsoft;
        this.provisioning = provisioning;
    }

    @Override
    public ResponseEntity<MicrosoftStartResponse> startMicrosoftLogin(MicrosoftStartRequest request) {
        properties.requireEnabled();
        if (!EmployeeAccess.companyEmail(request.getEmail())) throw new ApiException(HttpStatus.BAD_REQUEST,
                "MICROSOFT_EMAIL_REQUIRED", "Enter your Sails company email address.");
        var pending = authorizations.create(request.getCodeChallenge());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new MicrosoftStartResponse(
                microsoft.authorizationUrl(request.getEmail(), request.getCodeChallenge(), pending), pending.state()));
    }

    @Override
    public ResponseEntity<LoginResponse> completeMicrosoftLogin(MicrosoftCompleteRequest request) {
        properties.requireEnabled();
        String nonce = authorizations.consume(request.getState(), request.getCodeVerifier());
        var identity = microsoft.authenticate(request.getCode(), request.getCodeVerifier(), nonce);
        var result = provisioning.signIn(identity);
        var tokens = result.tokenPair();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new LoginResponse(
                tokens.accessToken(), tokens.refreshToken(), tokens.tokenType(), tokens.expiresIn(),
                UserResponseMapper.toResponse(result.user()), result.firstLogin()));
    }
}
