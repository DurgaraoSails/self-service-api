package com.sails.ai.selfserviceapi.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.onboarding.OnboardingRateLimiter.Purpose;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class OnboardingRateLimiterTest {

    private final OnboardingRateLimiter limiter = new OnboardingRateLimiter();

    @Test
    void allowsUpToTheLimitThenRefuses() {
        IntStream.range(0, OnboardingRateLimiter.MAX_CHECKS_PER_WINDOW)
                .forEach(i -> assertThat(limiter.tryAcquire("user-1", Purpose.CHECK)).isTrue());

        assertThat(limiter.tryAcquire("user-1", Purpose.CHECK)).isFalse();
    }

    /** The limit protects the shared GitHub token, but it must not let one user block everyone else. */
    @Test
    void limitsEachUserSeparately() {
        IntStream.range(0, OnboardingRateLimiter.MAX_CHECKS_PER_WINDOW + 1)
                .forEach(i -> limiter.tryAcquire("noisy-user", Purpose.CHECK));

        assertThat(limiter.tryAcquire("quiet-user", Purpose.CHECK)).isTrue();
    }

    /** An LLM call is far more expensive than a GitHub read, so generation gets its own, stricter budget. */
    @Test
    void generationHasItsOwnStricterBudgetIndependentOfChecks() {
        IntStream.range(0, OnboardingRateLimiter.MAX_GENERATIONS_PER_WINDOW)
                .forEach(i -> assertThat(limiter.tryAcquire("user-1", Purpose.GENERATE)).isTrue());

        assertThat(limiter.tryAcquire("user-1", Purpose.GENERATE)).isFalse();
        // Exhausting GENERATE must not touch the separate CHECK budget for the same user.
        assertThat(limiter.tryAcquire("user-1", Purpose.CHECK)).isTrue();
    }
}
