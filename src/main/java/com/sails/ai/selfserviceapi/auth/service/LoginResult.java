package com.sails.ai.selfserviceapi.auth.service;

import com.sails.ai.selfserviceapi.user.entity.User;

public record LoginResult(User user, TokenPair tokenPair, boolean firstLogin) {
}
