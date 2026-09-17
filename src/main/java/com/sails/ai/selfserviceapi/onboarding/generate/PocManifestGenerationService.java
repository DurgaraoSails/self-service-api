package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitBranchNames;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParseException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParser;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import com.sails.ai.selfserviceapi.onboarding.PocOnboardingCheckService;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImporter;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedService;
import com.sails.ai.selfserviceapi.onboarding.generate.dockerfile.DockerfilePlanner;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftException;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.DetectedStack;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackDetector;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;

/**
 * Orchestrates "turn a repository into a poc.yaml (and any missing Dockerfiles)" end to end.
 *
 * <p>The container plan comes from the first case that applies: a poc.yaml already there and
 * valid (never rewritten — only its Dockerfiles are checked), a single root Dockerfile and nothing
 * else (the platform's own synthesized default already covers it), the draft model (manifest-only,
 * given deterministic FACTS plus redacted evidence, with any cloudbuild.yaml import overlaid before
 * validation), or — when the model is unavailable or fails — {@link DeterministicContainerPlanner}'s
 * no-guessing fallback. {@link DockerfilePlanner} then decides each container's Dockerfile
 * independently of how the container plan itself was produced.
 *
 * <p>Every rule about what makes a manifest valid still lives in {@code ManifestValidator}, reached
 * through {@code ManifestService}/{@code ManifestParser} directly — this class never validates
 * anything itself, only assembles the response.
 */
@Service
public class PocManifestGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PocManifestGenerationService.class);
    private static final String TEMPLATE_RESOURCE = "templates/poc.yaml.template";

    private static final Set<String> UNSUPPORTED_IMPORT_CODES = Set.of(
            GenerationNoticeCode.UNSUPPORTED_SETTING, GenerationNoticeCode.FILE_SECRET_UNSUPPORTED,
            GenerationNoticeCode.SIDECAR_RESOURCES_UNSUPPORTED, GenerationNoticeCode.BUILD_ARG_UNSUPPORTED,
            GenerationNoticeCode.BUILD_TARGET_UNSUPPORTED, GenerationNoticeCode.BUILDPACKS_NEEDS_DOCKERFILE);

    private final GitHubService gitHubService;
    private final ManifestService manifestService;
    private final ManifestParser manifestParser;
    private final ManifestValidator manifestValidator;
    private final PocOnboardingCheckService checkService;
    private final RepoInventoryService inventoryService;
    private final CloudBuildImporter cloudBuildImporter;
    private final StackDetector stackDetector;
    private final ManifestDraftService draftService;
    private final ManifestMerger manifestMerger;
    private final DeterministicContainerPlanner deterministicPlanner;
    private final DockerfilePlanner dockerfilePlanner;
    private final ManifestYamlWriter yamlWriter;
    private final DraftModelProperties properties;

    public PocManifestGenerationService(GitHubService gitHubService, ManifestService manifestService,
                                         ManifestParser manifestParser, ManifestValidator manifestValidator,
                                         PocOnboardingCheckService checkService, RepoInventoryService inventoryService,
                                         CloudBuildImporter cloudBuildImporter, StackDetector stackDetector,
                                         ManifestDraftService draftService, ManifestMerger manifestMerger,
                                         DeterministicContainerPlanner deterministicPlanner, DockerfilePlanner dockerfilePlanner,
                                         ManifestYamlWriter yamlWriter, DraftModelProperties properties) {
        this.gitHubService = gitHubService;
        this.manifestService = manifestService;
        this.manifestParser = manifestParser;
        this.manifestValidator = manifestValidator;
        this.checkService = checkService;
        this.inventoryService = inventoryService;
        this.cloudBuildImporter = cloudBuildImporter;
        this.stackDetector = stackDetector;
        this.draftService = draftService;
        this.manifestMerger = manifestMerger;
        this.deterministicPlanner = deterministicPlanner;
        this.dockerfilePlanner = dockerfilePlanner;
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

    /**
     * A GitHub read can fail transiently anywhere past the initial branch-head check above (rate
     * limit, a momentary 5xx) — the tree listing, the manifest read, or any evidence read all reach
     * GitHub again. Every one of those failures degrades the same way an unreachable draft model
     * does, rather than escaping as this endpoint's own 502.
     */
    private PocManifestGenerationResult generateForCommit(GitHubRepoRef repo, String sha, OnboardingCheckResult checkResult) {
        try {
            return generateForCommitOrThrow(repo, sha, checkResult);
        } catch (GitHubApiException e) {
            log.debug("GitHub read failed mid-generation for {}: {}", repo, e.getMessage());
            return PocManifestGenerationResult.unavailable(checkResult,
                    "The repository could not be fully read (" + e.getMessage() + "). Here is the platform's "
                            + "own poc.yaml template instead.", loadTemplate());
        }
    }

    private PocManifestGenerationResult generateForCommitOrThrow(GitHubRepoRef repo, String sha, OnboardingCheckResult checkResult) {
        GitHubTree tree = gitHubService.listTree(repo, sha);
        RepoLayout layout = RepoLayout.of(tree);
        RepoFileReader fileReader = new RepoFileReader(gitHubService, repo, sha);
        CloudBuildImport cloudBuildImport = cloudBuildImporter.importFrom(layout, fileReader).orElse(null);

        String existingManifestYaml = null;
        PocManifest existingValidManifest = null;
        boolean correcting = false;
        try {
            ManifestResolution resolution = manifestService.resolveForBuild(repo, sha);
            if (resolution.rawYaml() != null) {
                existingValidManifest = resolution.manifest();
            }
        } catch (ManifestParseException | ManifestValidationException e) {
            // A poc.yaml exists but the checker already rejects it — correct it rather than
            // designing a new one from scratch.
            correcting = true;
            existingManifestYaml = readExistingManifestText(repo, sha);
        }

        if (existingValidManifest != null) {
            return planForExistingManifest(existingValidManifest, layout, fileReader, cloudBuildImport, checkResult);
        }

        boolean cloudBuildHasServices = cloudBuildImport != null && !cloudBuildImport.services().isEmpty();
        if (!correcting && !cloudBuildHasServices && isSingleRootDockerfileOnly(tree)) {
            return PocManifestGenerationResult.notNeeded(checkResult,
                    "This repository has a single Dockerfile at its root — the platform already deploys "
                            + "that as one ingress container named 'app'. You don't need a poc.yaml.");
        }

        return planContainersAndFiles(repo, sha, layout, fileReader, cloudBuildImport, existingManifestYaml, correcting, checkResult);
    }

    /** A valid poc.yaml is never rewritten — only its containers' Dockerfiles are checked for what's missing. */
    private PocManifestGenerationResult planForExistingManifest(PocManifest manifest, RepoLayout layout, RepoFileReader fileReader,
                                                                  CloudBuildImport cloudBuildImport, OnboardingCheckResult checkResult) {
        List<GenerationNotice> notices = new ArrayList<>();
        if (cloudBuildImport != null) {
            notices.addAll(cloudBuildImport.notices());
            notices.add(GenerationNotice.info(GenerationNoticeCode.IMPORT_NOT_APPLIED,
                    "This repository already has a valid poc.yaml — cloudbuild.yaml settings were not applied to it."));
        }

        DockerfilePlanner.PlanResult dockerfilePlan = dockerfilePlanner.plan(manifest, layout, fileReader);
        notices.addAll(dockerfilePlan.notices());

        if (dockerfilePlan.files().isEmpty()) {
            return PocManifestGenerationResult.notNeeded(checkResult,
                    "This repository already has a poc.yaml, and it passes the platform's validator — nothing to generate.");
        }
        return new PocManifestGenerationResult(PocManifestGenerationResult.Outcome.GENERATED, dockerfilePlan.files(),
                notices, toGenerationImports(cloudBuildImport), List.of(), false, checkResult);
    }

    private PocManifestGenerationResult planContainersAndFiles(GitHubRepoRef repo, String sha, RepoLayout layout,
                                                                 RepoFileReader fileReader, CloudBuildImport cloudBuildImport,
                                                                 String existingManifestYaml, boolean correcting,
                                                                 OnboardingCheckResult checkResult) {
        List<GenerationNotice> notices = new ArrayList<>();
        if (cloudBuildImport != null) {
            notices.addAll(cloudBuildImport.notices());
        }

        Map<String, DetectedStack> stacksByDirectory = detectStacks(layout, fileReader);
        ManifestFacts facts = buildFacts(stacksByDirectory, layout, cloudBuildImport);

        Optional<ManifestDraftModel> model = draftService.selectedModel();
        PocManifest manifest;
        List<String> assumptions = List.of();
        GeneratedFile.Source pocYamlSource;

        if (model.isPresent() && model.get().isAvailable()) {
            try {
                RepoInventory inventory = inventoryService.inventory(repo, sha);
                UnaryOperator<PocManifest> overlay = draft -> manifestMerger.applyOverlay(draft, cloudBuildImport);
                ManifestDraftResult draft = draftService.draft(inventory, facts, existingManifestYaml, overlay);
                manifest = draft.manifest();
                assumptions = draft.assumptions();
                notices.addAll(draft.notices());
                notices.addAll(manifestMerger.merge(draft.manifest(), cloudBuildImport).notices());
                if (inventory.treeTruncated()) {
                    notices.add(GenerationNotice.warning(GenerationNoticeCode.TREE_TRUNCATED,
                            "This repository is larger than GitHub would list in one read — the generator may not "
                                    + "have seen every file."));
                }
                pocYamlSource = GeneratedFile.Source.MODEL;
            } catch (ManifestDraftValidationException | ManifestDraftException e) {
                log.debug("Draft model call failed for {}: {}", repo, e.getMessage());
                notices.add(GenerationNotice.warning(GenerationNoticeCode.MODEL_UNAVAILABLE,
                        "The draft model could not produce a usable manifest (" + e.getMessage() + ") — falling "
                                + "back to what the platform could determine on its own."));
                Optional<PocManifest> fallback = deterministicPlanner.plan(stacksByDirectory, cloudBuildImport);
                if (fallback.isEmpty()) {
                    return PocManifestGenerationResult.unavailable(checkResult,
                            "The draft model could not produce a manifest (" + e.getMessage() + "), and this "
                                    + "repository's shape is not one the platform can determine on its own. Here is "
                                    + "the platform's own poc.yaml template instead.", loadTemplate());
                }
                manifest = fallback.get();
                pocYamlSource = GeneratedFile.Source.DERIVED;
            }
        } else {
            log.debug("poc-generator.provider='{}' is not available for {}", properties.provider(), repo);
            notices.add(GenerationNotice.warning(GenerationNoticeCode.MODEL_UNAVAILABLE,
                    "No draft model is reachable right now (poc-generator.provider='" + properties.provider() + "')."));
            Optional<PocManifest> fallback = deterministicPlanner.plan(stacksByDirectory, cloudBuildImport);
            if (fallback.isEmpty()) {
                return PocManifestGenerationResult.unavailable(checkResult,
                        "No draft model is reachable right now, and this repository's shape is not one the "
                                + "platform can determine on its own. Here is the platform's own poc.yaml template "
                                + "instead.", loadTemplate());
            }
            manifest = fallback.get();
            pocYamlSource = GeneratedFile.Source.DERIVED;
        }

        DockerfilePlanner.PlanResult dockerfilePlan = dockerfilePlanner.plan(manifest, layout, fileReader);
        notices.addAll(dockerfilePlan.notices());

        String pocYaml = yamlWriter.write(manifest, assumptions);
        List<String> violations = manifestValidator.validate(manifestParser.parse(pocYaml));
        if (!violations.isEmpty()) {
            log.debug("Generated poc.yaml failed re-validation for {}: {}", repo, violations);
            return PocManifestGenerationResult.unavailable(checkResult,
                    "The generated poc.yaml did not pass the platform's own validator (" + String.join("; ", violations)
                            + "). Here is the platform's own poc.yaml template instead.", loadTemplate());
        }

        List<GeneratedFile> files = new ArrayList<>();
        files.add(new GeneratedFile("poc.yaml", GeneratedFile.Kind.POC_YAML, GeneratedFile.Action.CREATE, pocYamlSource,
                null, pocYaml, correcting ? "Corrected the existing poc.yaml so it passes the platform's validator."
                : "Generated from the repository.", false));
        files.addAll(dockerfilePlan.files());

        return new PocManifestGenerationResult(PocManifestGenerationResult.Outcome.GENERATED, files, notices,
                toGenerationImports(cloudBuildImport), assumptions, correcting, checkResult);
    }

    private Map<String, DetectedStack> detectStacks(RepoLayout layout, RepoFileReader fileReader) {
        Map<String, DetectedStack> stacksByDirectory = new LinkedHashMap<>();
        for (String directory : layout.basenamesByDirectory().keySet()) {
            stacksByDirectory.put(directory, stackDetector.detect(fileReader, directory, layout.basenamesIn(directory)));
        }
        return stacksByDirectory;
    }

    private ManifestFacts buildFacts(Map<String, DetectedStack> stacksByDirectory, RepoLayout layout, CloudBuildImport cloudBuildImport) {
        List<ManifestFacts.ComponentFact> components = new ArrayList<>();
        for (Map.Entry<String, DetectedStack> entry : stacksByDirectory.entrySet()) {
            String directory = entry.getKey();
            boolean hasDockerfile = layout.directoriesWithDockerfile().contains(directory);
            String dockerfilePath = hasDockerfile
                    ? layout.dockerfiles().stream().filter(path -> directoryOf(path).equals(directory)).findFirst().orElse(null)
                    : null;
            components.add(new ManifestFacts.ComponentFact(directory, entry.getValue().kind(), hasDockerfile, dockerfilePath));
        }
        return new ManifestFacts(components, cloudBuildImport);
    }

    private List<GenerationImport> toGenerationImports(CloudBuildImport cloudBuildImport) {
        if (cloudBuildImport == null) {
            return List.of();
        }
        List<String> services = cloudBuildImport.services().stream().map(ImportedService::name).toList();
        List<GenerationImport.Setting> settings = new ArrayList<>();
        List<String> secretNames = new ArrayList<>();
        for (ImportedService service : cloudBuildImport.services()) {
            for (ImportedContainer container : service.containers()) {
                addSetting(settings, container.name(), "port", container.port() == null ? null : String.valueOf(container.port()));
                addSetting(settings, container.name(), "health", container.health());
                addSetting(settings, container.name(), "cpu", container.cpu());
                addSetting(settings, container.name(), "memory", container.memory());
                if (container.env() != null) {
                    container.env().forEach((key, value) -> addSetting(settings, container.name(), "env." + key, value));
                }
                if (container.secretEnvNames() != null) {
                    secretNames.addAll(container.secretEnvNames());
                }
            }
        }
        List<GenerationImport.Unsupported> unsupported = cloudBuildImport.notices().stream()
                .filter(notice -> UNSUPPORTED_IMPORT_CODES.contains(notice.code()))
                .map(notice -> new GenerationImport.Unsupported(notice.code(), notice.message()))
                .toList();
        return List.of(new GenerationImport(cloudBuildImport.sourcePath(), services, settings, secretNames, unsupported));
    }

    private void addSetting(List<GenerationImport.Setting> settings, String container, String key, String value) {
        if (value != null && !value.isBlank()) {
            settings.add(new GenerationImport.Setting(container, key, value));
        }
    }

    /**
     * Re-resolves the repo/branch the checker just validated, for the GitHub calls generation needs
     * beyond what a finding conveys (a {@link GitHubRepoRef} and a commit sha) — the checker itself
     * does not expose either. Empty when the checker's own findings already say this repository or
     * branch could not be read, so this never repeats work only to fail the same way.
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

    private String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
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
