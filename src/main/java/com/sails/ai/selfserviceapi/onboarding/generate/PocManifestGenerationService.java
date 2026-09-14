package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitBranchNames;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParseException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import com.sails.ai.selfserviceapi.onboarding.PocOnboardingCheckService;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

/**
 * Orchestrates "turn a repository into a poc.yaml" end to end. Every rule about what makes a
 * manifest valid still lives in {@code ManifestValidator}, reached through {@code ManifestService}
 * and {@code ManifestDraftService} — this class only decides which of the three outcomes applies
 * and assembles the response, never validates anything itself.
 */
@Service
public class PocManifestGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PocManifestGenerationService.class);
    private static final String TEMPLATE_RESOURCE = "templates/poc.yaml.template";

    private final GitHubService gitHubService;
    private final ManifestService manifestService;
    private final PocOnboardingCheckService checkService;
    private final RepoInventoryService inventoryService;
    private final ManifestDraftService draftService;
    private final ManifestYamlWriter yamlWriter;
    private final DraftModelProperties properties;

    public PocManifestGenerationService(GitHubService gitHubService, ManifestService manifestService,
                                         PocOnboardingCheckService checkService, RepoInventoryService inventoryService,
                                         ManifestDraftService draftService, ManifestYamlWriter yamlWriter,
                                         DraftModelProperties properties) {
        this.gitHubService = gitHubService;
        this.manifestService = manifestService;
        this.checkService = checkService;
        this.inventoryService = inventoryService;
        this.draftService = draftService;
        this.yamlWriter = yamlWriter;
        this.properties = properties;
    }

    public PocManifestGenerationResult generate(String githubUrl, String deployBranch) {
        // The checker already runs every precondition this needs (URL, access, branch) and reports
        // them as findings rather than throwing — reused rather than restated so this endpoint can
        // never disagree with what /poc-onboarding/check says about the same repository.
        OnboardingCheckResult checkResult = checkService.check(githubUrl, deployBranch, null);

        if (!properties.enabled()) {
            return PocManifestGenerationResult.unavailable(checkResult,
                    "poc.yaml generation is turned off on this platform.", loadTemplate());
        }

        Optional<GitHubRepoRef> repo = resolveRepo(githubUrl, deployBranch, checkResult);
        if (repo.isEmpty()) {
            return PocManifestGenerationResult.unavailable(checkResult,
                    "The repository or branch could not be read — see the check findings above.", loadTemplate());
        }
        String sha;
        try {
            sha = gitHubService.getBranchHeadSha(repo.get(), deployBranch.trim());
        } catch (GitHubApiException e) {
            return PocManifestGenerationResult.unavailable(checkResult,
                    "The repository or branch could not be read — see the check findings above.", loadTemplate());
        }

        return generateForCommit(repo.get(), sha, checkResult);
    }

    private PocManifestGenerationResult generateForCommit(GitHubRepoRef repo, String sha, OnboardingCheckResult checkResult) {
        String existingManifestYaml = null;
        boolean correcting = false;

        try {
            ManifestResolution resolution = manifestService.resolveForBuild(repo, sha);
            if (resolution.rawYaml() != null) {
                return PocManifestGenerationResult.notNeeded(checkResult,
                        "This repository already has a poc.yaml, and it passes the platform's validator — "
                                + "nothing to generate.");
            }
            // No manifest at all. A single root Dockerfile is exactly what the platform already
            // synthesizes by default (see ManifestService.synthesizeDefault) — a generated file here
            // would be pure noise.
            RepoInventory earlyInventory = inventoryService.inventory(repo, sha);
            if (isSingleRootDockerfileOnly(earlyInventory.tree())) {
                return PocManifestGenerationResult.notNeeded(checkResult,
                        "This repository has a single Dockerfile at its root — the platform already deploys "
                                + "that as one ingress container named 'app'. You don't need a poc.yaml.");
            }
            return runPipeline(repo, sha, earlyInventory, null, false, checkResult);
        } catch (ManifestParseException | ManifestValidationException e) {
            // A poc.yaml exists but the checker already rejects it — correct it rather than
            // designing a new one from scratch.
            correcting = true;
            existingManifestYaml = readExistingManifestText(repo, sha);
        }

        RepoInventory inventory = inventoryService.inventory(repo, sha);
        return runPipeline(repo, sha, inventory, existingManifestYaml, correcting, checkResult);
    }

    private PocManifestGenerationResult runPipeline(GitHubRepoRef repo, String sha, RepoInventory inventory,
                                                      String existingManifestYaml, boolean correcting,
                                                      OnboardingCheckResult checkResult) {
        Optional<ManifestDraftModel> model = draftService.selectedModel();
        if (model.isEmpty() || !model.get().isAvailable()) {
            log.debug("poc-generator.provider='{}' is not available for {}", properties.provider(), repo);
            return PocManifestGenerationResult.unavailable(checkResult,
                    "No draft model is reachable right now (poc-generator.provider='" + properties.provider()
                            + "'). Here is the platform's own poc.yaml template instead.", loadTemplate());
        }

        ManifestDraftResult draft;
        try {
            draft = draftService.draft(inventory, existingManifestYaml);
        } catch (ManifestDraftValidationException e) {
            return PocManifestGenerationResult.unavailable(checkResult,
                    "The draft model could not produce a manifest that passes the platform's validator: "
                            + String.join("; ", e.violations()), loadTemplate());
        }

        List<String> warnings = new ArrayList<>(draft.secretWarnings());
        if (inventory.treeTruncated()) {
            warnings.add("This repository is larger than GitHub would list in one read — the generator may not "
                    + "have seen every file.");
        }

        String pocYaml = yamlWriter.write(draft.manifest(), draft.assumptions());
        return new PocManifestGenerationResult(PocManifestGenerationResult.Outcome.GENERATED, pocYaml,
                draft.dockerfiles(), draft.assumptions(), warnings, correcting, checkResult);
    }

    /**
     * Re-resolves the repo/branch the checker just validated, for the two GitHub calls generation
     * needs beyond what a finding conveys (a {@link GitHubRepoRef} and a commit sha) — the checker
     * itself does not expose either. Empty when the checker's own findings already say this
     * repository or branch could not be read, so this never repeats work only to fail the same way.
     */
    private Optional<GitHubRepoRef> resolveRepo(String githubUrl, String deployBranch, OnboardingCheckResult checkResult) {
        if (deployBranch == null || !GitBranchNames.isValid(deployBranch.trim())) {
            return Optional.empty();
        }
        try {
            GitHubRepoRef repo = gitHubService.parseRepoUrl(githubUrl);
            if (gitHubService.checkPushAccess(repo) != GitHubService.RepoAccess.OK) {
                return Optional.empty();
            }
            return Optional.of(repo);
        } catch (GitHubApiException e) {
            return Optional.empty();
        }
    }

    private boolean isSingleRootDockerfileOnly(GitHubTree tree) {
        boolean hasRootDockerfile = tree.entries().stream()
                .anyMatch(e -> e.isBlob() && e.path().equals("Dockerfile"));
        long dockerfileCount = tree.entries().stream()
                .filter(GitHubTreeEntry::isBlob)
                .filter(e -> basename(e.path()).equalsIgnoreCase("Dockerfile"))
                .count();
        return hasRootDockerfile && dockerfileCount == 1;
    }

    private String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private String readExistingManifestText(GitHubRepoRef repo, String sha) {
        for (String path : ManifestService.MANIFEST_PATHS) {
            Optional<String> raw = gitHubService.getFileContent(repo, sha, path);
            if (raw.isPresent()) {
                return raw.get();
            }
        }
        return null;
    }

    /**
     * {@code docs/poc.yaml.template} is not on the runtime classpath by default — a build-time copy
     * (see {@code pom.xml}'s {@code maven-resources-plugin} execution) puts it at
     * {@value #TEMPLATE_RESOURCE} so the degraded path always has something to hand back.
     */
    private String loadTemplate() {
        try {
            byte[] bytes = FileCopyUtils.copyToByteArray(new ClassPathResource(TEMPLATE_RESOURCE).getInputStream());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("poc.yaml.template is missing from the classpath — check the "
                    + "maven-resources-plugin copy in pom.xml", e);
        }
    }
}
