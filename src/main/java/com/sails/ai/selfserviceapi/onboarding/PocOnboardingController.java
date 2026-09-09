package com.sails.ai.selfserviceapi.onboarding;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.api.OnboardingApi;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckRequest;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckResponse;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingFinding;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately not {@code @PreAuthorize("hasRole('ADMIN')")}, unlike every other deployment-shaped
 * endpoint. The onboarding guide is readable by any signed-in user so a team lead can judge
 * feasibility before anyone grants them admin, and a checker they cannot run would make that
 * pointless. It creates nothing and returns only findings about a repository the caller already
 * named.
 */
@RestController
public class PocOnboardingController implements OnboardingApi {

    private final PocOnboardingCheckService checkService;
    private final OnboardingRateLimiter rateLimiter;

    public PocOnboardingController(PocOnboardingCheckService checkService, OnboardingRateLimiter rateLimiter) {
        this.checkService = checkService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public ResponseEntity<PocOnboardingCheckResponse> checkPocOnboarding(
            PocOnboardingCheckRequest pocOnboardingCheckRequest) {

        if (!rateLimiter.tryAcquire(CurrentUser.id())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ONBOARDING_CHECK_RATE_LIMITED",
                    "Too many repository checks. Wait a minute and try again.");
        }

        OnboardingCheckResult result = checkService.check(
                pocOnboardingCheckRequest.getGithubUrl(),
                pocOnboardingCheckRequest.getDeployBranch(),
                pocOnboardingCheckRequest.getSlug());

        return ResponseEntity.ok(toResponse(result));
    }

    private PocOnboardingCheckResponse toResponse(OnboardingCheckResult result) {
        List<PocOnboardingFinding> findings = result.findings().stream()
                .map(this::toFinding)
                .toList();

        return new PocOnboardingCheckResponse(result.repository(), result.ready(), findings)
                .manifestPresent(result.manifestPresent());
    }

    private PocOnboardingFinding toFinding(OnboardingFinding finding) {
        return new PocOnboardingFinding(finding.checkId(), finding.severity(), finding.title(), finding.detail())
                .fix(finding.fix());
    }
}
