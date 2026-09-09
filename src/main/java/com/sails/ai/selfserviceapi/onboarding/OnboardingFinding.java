package com.sails.ai.selfserviceapi.onboarding;

import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckId;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingSeverity;

/**
 * One thing {@link PocOnboardingCheckService} looked at and what the team should do about it.
 *
 * <p>{@code checkId} is the stable half. The portal's readiness checklist is tied to these ids in
 * a test rather than to the wording, so a message can be improved without silently detaching the
 * guide from the checks the API actually runs.
 */
public record OnboardingFinding(
        PocOnboardingCheckId checkId,
        PocOnboardingSeverity severity,
        String title,
        String detail,
        String fix
) {

    public static OnboardingFinding error(PocOnboardingCheckId checkId, String title, String detail, String fix) {
        return new OnboardingFinding(checkId, PocOnboardingSeverity.ERROR, title, detail, fix);
    }

    public static OnboardingFinding warning(PocOnboardingCheckId checkId, String title, String detail, String fix) {
        return new OnboardingFinding(checkId, PocOnboardingSeverity.WARNING, title, detail, fix);
    }

    public static OnboardingFinding info(PocOnboardingCheckId checkId, String title, String detail, String fix) {
        return new OnboardingFinding(checkId, PocOnboardingSeverity.INFO, title, detail, fix);
    }

    public boolean isError() {
        return severity == PocOnboardingSeverity.ERROR;
    }
}
