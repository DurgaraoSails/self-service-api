package com.sails.ai.selfserviceapi.auth.service;

public record TokenPair(String accessToken, String refreshToken, String tokenType, long expiresIn) {
}
