package com.sails.ai.selfserviceapi.auth.controller;

import com.sails.ai.selfserviceapi.auth.service.OtpService;
import com.sails.ai.selfserviceapi.generated.api.AuthApi;
import com.sails.ai.selfserviceapi.generated.model.OtpRequestRequest;
import com.sails.ai.selfserviceapi.generated.model.OtpRequestResponse;
import com.sails.ai.selfserviceapi.generated.model.OtpVerifyRequest;
import com.sails.ai.selfserviceapi.generated.model.OtpVerifyResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController implements AuthApi {

    private final OtpService otpService;

    public AuthController(OtpService otpService) {
        this.otpService = otpService;
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
    public ResponseEntity<OtpVerifyResponse> verifyOtp(OtpVerifyRequest otpVerifyRequest) {
        String userId = otpService.verifyOtp(otpVerifyRequest.getEmail(), otpVerifyRequest.getCode());
        return ResponseEntity.ok(new OtpVerifyResponse()
                .verified(true)
                .userId(userId));
    }
}
