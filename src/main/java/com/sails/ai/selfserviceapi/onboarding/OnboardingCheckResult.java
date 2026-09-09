package com.sails.ai.selfserviceapi.onboarding;

import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckId;
import java.util.List;

/**
 * Everything one run of the checker found.
 *
 * <p>{@code ready} means "no ERROR finding" and nothing more. The platform can read a repository
 * but cannot run its image, so a ready result is a statement about the repo's shape, never a
 * promise that the POC works — which is why the guide's checklist marks the rows this cannot
 * cover.
 *
 * @param checksPassed the checks that ran and found nothing wrong. Deliberately recorded rather
 *                     than derived from {@code findings}: the checker stops at the first blocking
 *                     problem, so a check with no finding against it may simply never have run, and
 *                     treating that absence as success would tick a box for something never looked
 *                     at.
 * @param availableBranches the repository's branches, populated only when the requested branch does
 *                          not exist. Null otherwise, including on success.
 */
public record OnboardingCheckResult(
        String repository,
        boolean manifestPresent,
        List<PocOnboardingCheckId> checksPassed,
        List<OnboardingFinding> findings,
        List<String> availableBranches) {

    public OnboardingCheckResult(String repository, boolean manifestPresent,
                                  List<PocOnboardingCheckId> checksPassed, List<OnboardingFinding> findings) {
        this(repository, manifestPresent, checksPassed, findings, null);
    }

    public boolean ready() {
        return findings.stream().noneMatch(OnboardingFinding::isError);
    }
}
