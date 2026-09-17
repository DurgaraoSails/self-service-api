package com.sails.ai.selfserviceapi.onboarding;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.generated.api.OnboardingApi;
import com.sails.ai.selfserviceapi.generated.model.PocGeneratedDockerfile;
import com.sails.ai.selfserviceapi.generated.model.PocGeneratedFile;
import com.sails.ai.selfserviceapi.generated.model.PocGenerationImport;
import com.sails.ai.selfserviceapi.generated.model.PocGenerationImportSettingsInner;
import com.sails.ai.selfserviceapi.generated.model.PocGenerationImportUnsupportedInner;
import com.sails.ai.selfserviceapi.generated.model.PocGenerationNotice;
import com.sails.ai.selfserviceapi.generated.model.PocManifestGenerateRequest;
import com.sails.ai.selfserviceapi.generated.model.PocManifestGenerateResponse;
import com.sails.ai.selfserviceapi.generated.model.PocManifestGenerationOutcome;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckRequest;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckResponse;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingFinding;
import com.sails.ai.selfserviceapi.onboarding.generate.GeneratedFile;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationImport;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.PocManifestGenerationResult;
import com.sails.ai.selfserviceapi.onboarding.generate.PocManifestGenerationService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately not {@code @PreAuthorize("hasRole('ADMIN')")}, unlike every other deployment-shaped
 * endpoint. The onboarding guide is readable by any signed-in user so a team lead can judge
 * feasibility before anyone grants them admin, and a checker they cannot run would make that
 * pointless. It creates nothing and returns only findings — and, for generation, a manifest already
 * validated by the same rules a real deploy enforces — about a repository the caller already named.
 */
@RestController
public class PocOnboardingController implements OnboardingApi {

    private final PocOnboardingCheckService checkService;
    private final PocManifestGenerationService generationService;
    private final OnboardingRateLimiter rateLimiter;

    public PocOnboardingController(PocOnboardingCheckService checkService,
                                    PocManifestGenerationService generationService,
                                    OnboardingRateLimiter rateLimiter) {
        this.checkService = checkService;
        this.generationService = generationService;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public ResponseEntity<PocOnboardingCheckResponse> checkPocOnboarding(
            PocOnboardingCheckRequest pocOnboardingCheckRequest) {

        if (!rateLimiter.tryAcquire(CurrentUser.id(), OnboardingRateLimiter.Purpose.CHECK)) {
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

        return new PocOnboardingCheckResponse(result.repository(), result.ready(), result.checksPassed(), findings)
                .manifestPresent(result.manifestPresent())
                .availableBranches(result.availableBranches());
    }

    private PocOnboardingFinding toFinding(OnboardingFinding finding) {
        return new PocOnboardingFinding(finding.checkId(), finding.severity(), finding.title(), finding.detail())
                .fix(finding.fix());
    }

    @Override
    public ResponseEntity<PocManifestGenerateResponse> generatePocManifest(
            PocManifestGenerateRequest pocManifestGenerateRequest) {

        if (!rateLimiter.tryAcquire(CurrentUser.id(), OnboardingRateLimiter.Purpose.GENERATE)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ONBOARDING_GENERATE_RATE_LIMITED",
                    "Too many generation requests. Wait a few minutes and try again.");
        }

        PocManifestGenerationResult result = generationService.generate(
                pocManifestGenerateRequest.getGithubUrl(), pocManifestGenerateRequest.getDeployBranch());

        return ResponseEntity.ok(toResponse(result));
    }

    private PocManifestGenerateResponse toResponse(PocManifestGenerationResult result) {
        List<PocGeneratedFile> files = result.files().stream().map(this::toGeneratedFile).toList();
        List<PocGenerationNotice> notices = result.notices().stream().map(this::toNotice).toList();
        List<PocGenerationImport> imports = result.imports().stream().map(this::toImport).toList();
        List<PocGeneratedDockerfile> legacyDockerfiles = result.dockerfiles().stream()
                .map(f -> new PocGeneratedDockerfile(f.path(), f.content(), f.reason()))
                .toList();

        return new PocManifestGenerateResponse(
                PocManifestGenerationOutcome.valueOf(result.outcome().name()),
                files, notices, imports, legacyDockerfiles,
                result.assumptions(), result.warnings(),
                result.manifestWasCorrected(),
                toResponse(result.checkResult()))
                .pocYaml(result.pocYaml());
    }

    private PocGeneratedFile toGeneratedFile(GeneratedFile file) {
        return new PocGeneratedFile(file.path(),
                PocGeneratedFile.KindEnum.valueOf(file.kind().name()),
                PocGeneratedFile.ActionEnum.valueOf(file.action().name()),
                PocGeneratedFile.SourceEnum.valueOf(file.source().name()),
                file.content(), file.reason(), file.needsReview())
                .container(file.container());
    }

    private PocGenerationNotice toNotice(GenerationNotice notice) {
        return new PocGenerationNotice(notice.severity(), notice.code(), notice.message())
                .path(notice.path())
                .container(notice.container());
    }

    private PocGenerationImport toImport(GenerationImport generationImport) {
        List<PocGenerationImportSettingsInner> settings = generationImport.settings().stream()
                .map(s -> new PocGenerationImportSettingsInner(s.container(), s.key(), s.value()))
                .toList();
        List<PocGenerationImportUnsupportedInner> unsupported = generationImport.unsupported().stream()
                .map(u -> new PocGenerationImportUnsupportedInner(u.setting(), u.reason()))
                .toList();
        return new PocGenerationImport(generationImport.sourcePath(), generationImport.services(), settings,
                generationImport.secretNames(), unsupported);
    }
}
