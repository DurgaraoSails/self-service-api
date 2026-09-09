package com.sails.ai.selfserviceapi.onboarding;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService.RepoAccess;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParseException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckId;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Answers "is this repository ready to be onboarded?" without creating anything.
 *
 * <p>Every rule it reports comes from the code a real deploy runs — {@link GitHubService}'s
 * repository preconditions and {@link ManifestService}'s parse-and-validate — reached by calling
 * them, not by restating them. That is the entire point of the endpoint: the onboarding guide can
 * describe the contract in prose and still never disagree with the validator, because the answer a
 * team acts on is produced by the validator itself.
 *
 * <p>It reports rather than throws. An unready repository is the expected result here, so every
 * problem is collected and returned together; a team fixing three things learns about all three in
 * one pass instead of one per retry.
 */
@Service
public class PocOnboardingCheckService {

    private static final String MANIFEST_PATH = "poc.yaml";

    private final GitHubService gitHubService;
    private final ManifestService manifestService;
    private final PocRepository pocRepository;

    public PocOnboardingCheckService(GitHubService gitHubService, ManifestService manifestService,
                                      PocRepository pocRepository) {
        this.gitHubService = gitHubService;
        this.manifestService = manifestService;
        this.pocRepository = pocRepository;
    }

    public OnboardingCheckResult check(String githubUrl, String slug) {
        List<OnboardingFinding> findings = new ArrayList<>();

        GitHubRepoRef repo;
        try {
            repo = gitHubService.parseRepoUrl(githubUrl);
        } catch (GitHubApiException e) {
            findings.add(OnboardingFinding.error(PocOnboardingCheckId.REPO_URL,
                    "That is not a GitHub repository URL",
                    e.getMessage(),
                    "Use the repository's own URL, for example https://github.com/your-org/your-repo."));
            return new OnboardingCheckResult(githubUrl, false, findings);
        }

        checkSlug(slug, findings);

        RepoAccess access = gitHubService.checkPushAccess(repo);
        if (access != RepoAccess.OK) {
            findings.add(accessFinding(access, repo));
            // Nothing below can run: reading poc.yaml and each Dockerfile needs the same access
            // that just failed, and a cascade of "could not read" findings would bury the one
            // problem the team actually has to fix.
            return new OnboardingCheckResult(repo.toString(), false, findings);
        }

        String commitSha;
        try {
            commitSha = gitHubService.getDeployBranchHeadSha(repo);
        } catch (GitHubApiException e) {
            findings.add(OnboardingFinding.error(PocOnboardingCheckId.REPO_ACCESS,
                    "Could not read the branch this POC would deploy from",
                    e.getMessage(),
                    "Make sure that branch exists and has at least one commit."));
            return new OnboardingCheckResult(repo.toString(), false, findings);
        }

        return resolveManifest(repo, commitSha, findings);
    }

    /**
     * Resolves the manifest exactly as a build would, turning the two exceptions that path throws
     * into findings. {@link ManifestValidationException} carries every violation the validator
     * found, so each becomes its own finding rather than one line with semicolons in it.
     */
    private OnboardingCheckResult resolveManifest(GitHubRepoRef repo, String commitSha,
                                                   List<OnboardingFinding> findings) {
        ManifestResolution resolution;
        try {
            resolution = manifestService.resolveForBuild(repo, commitSha);
        } catch (ManifestParseException e) {
            findings.add(OnboardingFinding.error(PocOnboardingCheckId.MANIFEST_PARSE,
                    "poc.yaml could not be parsed",
                    e.getMessage(),
                    "Check that the file is valid YAML and that 'containers' is a list."));
            return new OnboardingCheckResult(repo.toString(), true, findings);
        } catch (ManifestValidationException e) {
            e.getViolations().forEach(violation -> findings.add(OnboardingFinding.error(
                    PocOnboardingCheckId.MANIFEST_VALIDATION,
                    "poc.yaml is invalid",
                    violation,
                    null)));
            return new OnboardingCheckResult(repo.toString(), true, findings);
        }

        boolean manifestPresent = resolution.rawYaml() != null;
        if (!manifestPresent) {
            findings.add(OnboardingFinding.info(PocOnboardingCheckId.MANIFEST_ABSENT,
                    "No poc.yaml, so the single-container default will be used",
                    "This repository has no " + MANIFEST_PATH + " at the commit that would be deployed, so the "
                            + "platform assumes one ingress container named 'app', built from 'Dockerfile' at the "
                            + "repository root. That is a complete, valid setup, not a problem.",
                    "Add a poc.yaml only if you need more than one container, a Dockerfile somewhere other than "
                            + "the repository root, or custom resources and scaling."));
        }

        for (ManifestContainer container : resolution.manifest().containers()) {
            checkDockerfile(repo, commitSha, container, findings);
            checkSidecarHealth(container, findings);
        }

        return new OnboardingCheckResult(repo.toString(), manifestPresent, findings);
    }

    /**
     * The one precondition the validator cannot check, because it needs the repository rather than
     * the manifest: a declared Dockerfile that does not exist fails inside Cloud Build, minutes
     * into a deploy, with a message about a build context rather than about poc.yaml.
     */
    private void checkDockerfile(GitHubRepoRef repo, String commitSha, ManifestContainer container,
                                  List<OnboardingFinding> findings) {
        if (gitHubService.getFileContent(repo, commitSha, container.dockerfile()).isPresent()) {
            return;
        }
        findings.add(OnboardingFinding.error(PocOnboardingCheckId.DOCKERFILE_PRESENT,
                "No Dockerfile at '" + container.dockerfile() + "'",
                "Container '" + container.name() + "' declares its Dockerfile at '" + container.dockerfile()
                        + "', and no file exists there at the commit that would be deployed. Both 'dockerfile' "
                        + "and 'context' are paths from the repository root, independently — 'dockerfile' is not "
                        + "resolved relative to 'context'.",
                "Add the Dockerfile at that path, or correct the 'dockerfile' value for container '"
                        + container.name() + "'."));
    }

    /**
     * A warning, not an error, and the distinction is the point. A sidecar with no {@code health:}
     * deploys perfectly well — it just has no startup probe, and Cloud Run only accepts a startup
     * dependency on a container that has one. So the ingress is never ordered behind it and can
     * proxy to a backend that is not listening yet. That surfaces as an intermittent 502 on cold
     * start, about as far from its cause as a symptom gets.
     */
    private void checkSidecarHealth(ManifestContainer container, List<OnboardingFinding> findings) {
        if (container.role() != ContainerRole.SIDECAR) {
            return;
        }
        if (container.health() != null && !container.health().isBlank()) {
            return;
        }
        findings.add(OnboardingFinding.warning(PocOnboardingCheckId.SIDECAR_HEALTH,
                "Sidecar '" + container.name() + "' declares no health path",
                "It will still deploy, but the ingress cannot be made to wait for it: Cloud Run only accepts a "
                        + "startup dependency on a container that has a startup probe. Without one, the ingress "
                        + "can receive traffic and proxy to this sidecar before it is listening, which shows up "
                        + "as a 502 on the first request after the service scales up from zero.",
                "Add 'health: /healthz' to container '" + container.name() + "', served on the port it declares."));
    }

    private void checkSlug(String slug, List<OnboardingFinding> findings) {
        if (slug == null || slug.isBlank()) {
            return;
        }
        String trimmed = slug.trim();
        if (pocRepository.findBySlugAndDeletedAtIsNull(trimmed).isEmpty()) {
            return;
        }
        findings.add(OnboardingFinding.error(PocOnboardingCheckId.SLUG_AVAILABLE,
                "The slug '" + trimmed + "' is already taken",
                "A slug identifies a POC in its launch URL, so it is unique across the whole portal.",
                "Pick a different slug — adding your team or product name is usually enough."));
    }

    private OnboardingFinding accessFinding(RepoAccess access, GitHubRepoRef repo) {
        return switch (access) {
            case NOT_FOUND -> OnboardingFinding.error(PocOnboardingCheckId.REPO_ACCESS,
                    "The platform cannot see this repository",
                    access.describe(repo),
                    "Check the URL, and if the repository is private, add the platform's GitHub account as a "
                            + "collaborator.");
            case ARCHIVED -> OnboardingFinding.error(PocOnboardingCheckId.REPO_ARCHIVED,
                    "This repository is archived",
                    access.describe(repo),
                    "Unarchive it in the repository's settings on GitHub.");
            case NO_PUSH -> OnboardingFinding.error(PocOnboardingCheckId.REPO_PUSH_ACCESS,
                    "The platform can read this repository but cannot write to it",
                    access.describe(repo),
                    "Add the platform's GitHub account as a collaborator with write access. Deploying creates a "
                            + "release tag, and a tag is a write — read access is not enough, even for a public "
                            + "repository.");
            case OK -> throw new IllegalArgumentException("OK is not a finding");
        };
    }
}
