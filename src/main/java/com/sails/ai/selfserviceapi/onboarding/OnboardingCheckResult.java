package com.sails.ai.selfserviceapi.onboarding;

import java.util.List;

/**
 * Everything one run of the checker found.
 *
 * <p>{@code ready} means "no ERROR finding" and nothing more. The platform can read a repository
 * but cannot run its image, so a ready result is a statement about the repo's shape, never a
 * promise that the POC works — which is why the guide's checklist marks the rows this cannot
 * cover.
 */
public record OnboardingCheckResult(String repository, boolean manifestPresent, List<OnboardingFinding> findings) {

    public boolean ready() {
        return findings.stream().noneMatch(OnboardingFinding::isError);
    }
}
