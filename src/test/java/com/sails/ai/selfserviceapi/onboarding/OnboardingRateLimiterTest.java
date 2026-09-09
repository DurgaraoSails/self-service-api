package com.sails.ai.selfserviceapi.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OnboardingRateLimiterTest {

    private final OnboardingRateLimiter limiter = new OnboardingRateLimiter();

    @Test
    void allowsUpToTheLimitThenRefuses() {
        IntStream.range(0, OnboardingRateLimiter.MAX_CHECKS_PER_WINDOW)
                .forEach(i -> assertThat(limiter.tryAcquire("user-1")).isTrue());

        assertThat(limiter.tryAcquire("user-1")).isFalse();
    }

    /** The limit protects the shared GitHub token, but it must not let one user block everyone else. */
    @Test
    void limitsEachUserSeparately() {
        IntStream.range(0, OnboardingRateLimiter.MAX_CHECKS_PER_WINDOW + 1)
                .forEach(i -> limiter.tryAcquire("noisy-user"));

        assertThat(limiter.tryAcquire("quiet-user")).isTrue();
    }
}
