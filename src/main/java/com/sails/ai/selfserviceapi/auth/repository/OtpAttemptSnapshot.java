package com.sails.ai.selfserviceapi.auth.repository;

public record OtpAttemptSnapshot(int attemptCount, String otpHash) {
}
