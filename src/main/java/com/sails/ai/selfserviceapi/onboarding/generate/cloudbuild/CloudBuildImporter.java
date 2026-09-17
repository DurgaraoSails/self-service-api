package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoLayout;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildConfigParser.Config;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildConfigParser.Step;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.GcloudRunDeployParser.RawContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.GcloudRunDeployParser.RawDeploy;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.StepCommandExtractor.ExtractedCommand;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.StepCommandExtractor.Tool;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EnvVarClassifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reads a repository's cloudbuild.yaml and produces a redacted, structural {@link CloudBuildImport}
 * — never the raw file, never a value a secret heuristic would flag. This is the one place all the
 * cloudbuild-parsing primitives ({@link ShellWords}, {@link CloudBuildConfigParser},
 * {@link StepCommandExtractor}, {@link ImageBuildParser}, {@link GcloudRunDeployParser}) come
 * together, plus {@link EnvVarClassifier} for turning each container's raw env into the same
 * env:/requires: split the rest of the pipeline already understands.
 *
 * <p>Only the first cloudbuild file found is imported, even though {@link RepoLayout} may report
 * several (the 5-file/100KB caps below bound how many are even considered) — merging facts from
 * multiple cloudbuild files that might disagree is a real design question this class does not try
 * to answer; one file is the overwhelmingly common shape.
 */
@Component
public class CloudBuildImporter {

    private static final Logger log = LoggerFactory.getLogger(CloudBuildImporter.class);

    private static final int MAX_CANDIDATE_FILES = 5;
    private static final long MAX_FILE_BYTES = 100_000;
    private static final int FIRST_SIDECAR_PORT = 8081;

    private final CloudBuildConfigParser configParser = new CloudBuildConfigParser();
    private final StepCommandExtractor commandExtractor = new StepCommandExtractor();
    private final ImageBuildParser buildParser = new ImageBuildParser();
    private final GcloudRunDeployParser deployParser = new GcloudRunDeployParser();
    private final EnvVarClassifier envClassifier;

    public CloudBuildImporter(EnvVarClassifier envClassifier) {
        this.envClassifier = envClassifier;
    }

    /** Empty (never null) when the repo has no cloudbuild file — the caller proceeds with no import rather than treating this as a failure. */
    public Optional<CloudBuildImport> importFrom(RepoLayout layout, RepoFileReader fileReader) {
        List<String> candidates = layout.cloudbuildFiles().stream().limit(MAX_CANDIDATE_FILES).toList();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        String path = candidates.get(0);
        String raw = fileReader.read(path).orElse(null);
        if (raw == null || raw.length() > MAX_FILE_BYTES) {
            return Optional.empty();
        }

        List<GenerationNotice> notices = new ArrayList<>();
        Config config;
        try {
            config = configParser.parse(raw);
        } catch (RuntimeException e) {
            log.debug("cloudbuild.yaml at {} could not be parsed: {}", path, e.getMessage());
            return Optional.of(new CloudBuildImport(path, List.of(), List.of(),
                    List.of(), List.of(GenerationNotice.warning(GenerationNoticeCode.CLOUDBUILD_PARSE_FAILED,
                            "'" + path + "' could not be parsed as a Cloud Build config (" + e.getMessage()
                                    + ") — nothing was imported from it."))));
        }

        List<ImportedBuild> builds = new ArrayList<>();
        List<RawDeploy> rawDeploys = new ArrayList<>();
        List<List<String>> preBuildSteps = new ArrayList<>();

        for (Step step : config.steps()) {
            for (ExtractedCommand command : commandExtractor.extract(step, config.optionsEnv())) {
                classifyCommand(command, builds, rawDeploys, preBuildSteps, notices);
            }
        }

        List<ImportedService> services = toImportedServices(rawDeploys, builds, notices);

        if (!services.isEmpty() || !builds.isEmpty()) {
            notices.add(0, GenerationNotice.info(GenerationNoticeCode.CLOUDBUILD_IMPORTED,
                    "Imported settings from '" + path + "'."));
        }

        return Optional.of(new CloudBuildImport(path, services, builds, preBuildSteps, notices));
    }

    private void classifyCommand(ExtractedCommand command, List<ImportedBuild> builds, List<RawDeploy> rawDeploys,
                                  List<List<String>> preBuildSteps, List<GenerationNotice> notices) {
        List<String> words = command.words();
        if (words.isEmpty()) {
            return;
        }
        switch (command.tool()) {
            case DOCKER -> classifyDockerCommand(words, command.dir(), builds, notices);
            case KANIKO -> buildParser.parseKaniko(words, command.dir())
                    .ifPresent(result -> builds.add(result.build()));
            case GCLOUD -> classifyGcloudCommand(words, rawDeploys, notices);
            case PACK -> {
                if (words.get(0).equals("pack") && words.contains("build")) {
                    notices.add(buildParser.buildpacksNeedsDockerfileNotice());
                }
            }
            case PRE_BUILD -> preBuildSteps.add(words);
            case UNKNOWN -> {
                // Deliberately silent for commands whose own tool isn't recognized at all (mkdir,
                // cp, cd, ...) — flagging every shell utility a build script happens to run would
                // drown out the notices that matter. Only a docker/gcloud command this class failed
                // to make sense of gets CLOUDBUILD_STEP_NOT_UNDERSTOOD, from the two branches above.
            }
        }
    }

    private void classifyDockerCommand(List<String> words, String dir, List<ImportedBuild> builds, List<GenerationNotice> notices) {
        Optional<ImageBuildParser.Result> build = buildParser.parseDockerBuild(words, dir);
        if (build.isPresent()) {
            builds.add(build.get().build());
            notices.addAll(build.get().notices());
            return;
        }
        String sub = words.size() > 1 ? words.get(1) : "";
        if (sub.equals("push") || sub.equals("tag")) {
            return; // ignored — nothing this platform needs to know
        }
        notices.add(GenerationNotice.info(GenerationNoticeCode.CLOUDBUILD_STEP_NOT_UNDERSTOOD,
                "A docker step ('" + String.join(" ", words) + "') was not recognized as a build this platform "
                        + "can use."));
    }

    private void classifyGcloudCommand(List<String> words, List<RawDeploy> rawDeploys, List<GenerationNotice> notices) {
        Optional<RawDeploy> deploy = deployParser.parse(words);
        if (deploy.isPresent()) {
            rawDeploys.add(deploy.get());
            notices.addAll(deploy.get().notices());
            return;
        }
        String joined = String.join(" ", words);
        if (containsSubcommand(words, "run", "services", "replace")) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.CLOUD_RUN_YAML_NOT_IMPORTED,
                    "'" + joined + "' deploys from a Knative service YAML, which this importer does not read."));
        } else if (containsSubcommand(words, "app", "deploy") || containsSubcommand(words, "functions", "deploy")
                || containsSubcommand(words, "run", "jobs")) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.OTHER_DEPLOY_TARGET,
                    "'" + joined + "' deploys to a different product than Cloud Run services — not imported."));
        } else if (words.contains("--pack") && containsSubcommand(words, "builds", "submit")) {
            notices.add(buildParser.buildpacksNeedsDockerfileNotice());
        } else if (containsSubcommand(words, "run", "services", "update-traffic")
                || containsSubcommand(words, "projects", "add-iam-policy-binding")) {
            // ignored — platform-managed
        } else {
            notices.add(GenerationNotice.info(GenerationNoticeCode.CLOUDBUILD_STEP_NOT_UNDERSTOOD,
                    "A gcloud step ('" + joined + "') was not recognized as something this platform imports."));
        }
    }

    private boolean containsSubcommand(List<String> words, String... subcommand) {
        for (int start = 0; start + subcommand.length <= words.size(); start++) {
            boolean matches = true;
            for (int j = 0; j < subcommand.length; j++) {
                if (!words.get(start + j).equals(subcommand[j])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * More than one {@code gcloud run deploy} in one file is merged into a single service — this
     * platform runs one Cloud Run service with sidecars, never several independent ones. The first
     * deploy's own containers keep their declared ports; every later deploy's containers get the
     * next free port from {@value #FIRST_SIDECAR_PORT} up when they declared none.
     */
    private List<ImportedService> toImportedServices(List<RawDeploy> rawDeploys, List<ImportedBuild> builds,
                                                       List<GenerationNotice> notices) {
        if (rawDeploys.isEmpty()) {
            return List.of();
        }
        List<ImportedContainer> allContainers = new ArrayList<>();
        int nextSidecarPort = FIRST_SIDECAR_PORT;
        String serviceName = rawDeploys.get(0).serviceName();
        Integer minInstances = null;
        Integer maxInstances = null;

        for (int deployIndex = 0; deployIndex < rawDeploys.size(); deployIndex++) {
            RawDeploy deploy = rawDeploys.get(deployIndex);
            minInstances = firstNonNull(minInstances, deploy.minInstances());
            maxInstances = firstNonNull(maxInstances, deploy.maxInstances());
            for (RawContainer raw : deploy.containers()) {
                Integer port = raw.port();
                if (port == null && deployIndex > 0) {
                    port = nextSidecarPort;
                }
                if (port != null && port >= nextSidecarPort) {
                    nextSidecarPort = port + 1;
                }
                String name = raw.name() != null ? raw.name() : deploy.serviceName();

                ImportedBuild linkedBuild = findBuildForImage(builds, raw.imageRef());
                String dockerfile = linkedBuild != null ? linkedBuild.dockerfile() : null;
                String context = linkedBuild != null ? linkedBuild.context() : null;
                if (dockerfile == null && raw.sourceDir() != null) {
                    // --source with no matching 'docker build'/kaniko step for its image means the
                    // deploy relies on Cloud Native Buildpacks, which this platform does not run.
                    context = raw.sourceDir();
                    notices.add(buildParser.buildpacksNeedsDockerfileNotice());
                }

                // No other imported service's real URL is knowable from static parsing alone (that
                // would need a live Cloud Run lookup, which this deterministic importer never does)
                // — rule 6 of EnvVarClassifier simply never fires here, which is the honest answer.
                EnvVarClassifier.Result classified = envClassifier.classify(raw.rawEnv(), port != null, Map.of());
                notices.addAll(classified.notices());
                notices.addAll(raw.notices());
                if (!raw.secretEnvNames().isEmpty()) {
                    notices.add(GenerationNotice.info(GenerationNoticeCode.SECRET_TO_PROVISION,
                            "Container '" + name + "' declares " + raw.secretEnvNames().size()
                                    + " secret(s) from --set-secrets — provision them in Secret Manager before deploying."));
                }
                allContainers.add(new ImportedContainer(name, dockerfile, context,
                        port != null ? port : classified.port(), raw.health(), raw.cpu(), raw.memory(),
                        classified.env(), raw.secretEnvNames()));
            }
        }

        if (rawDeploys.size() > 1) {
            notices.add(GenerationNotice.warning(GenerationNoticeCode.SERVICES_MERGED,
                    "This cloudbuild.yaml deploys " + rawDeploys.size() + " separate Cloud Run services; the "
                            + "platform runs one service with sidecars instead. They now talk to each other over "
                            + "localhost, with no IAM boundary between them — review whether that changes anything."));
        }

        return List.of(new ImportedService(serviceName, allContainers, minInstances, maxInstances, null));
    }

    private Integer firstNonNull(Integer a, Integer b) {
        return a != null ? a : b;
    }

    /** Links a deploy's {@code --image} to the build that produced it, tag and digest stripped from both sides. */
    private ImportedBuild findBuildForImage(List<ImportedBuild> builds, String imageRef) {
        if (imageRef == null) {
            return null;
        }
        String target = stripImageRef(imageRef);
        for (ImportedBuild build : builds) {
            for (String ref : build.imageRefs()) {
                if (stripImageRef(ref).equals(target)) {
                    return build;
                }
            }
        }
        return null;
    }

    private String stripImageRef(String ref) {
        int at = ref.indexOf('@');
        String withoutDigest = at >= 0 ? ref.substring(0, at) : ref;
        int lastColon = withoutDigest.lastIndexOf(':');
        int lastSlash = withoutDigest.lastIndexOf('/');
        // A colon before the last slash is a registry host:port, not a tag separator.
        return lastColon > lastSlash ? withoutDigest.substring(0, lastColon) : withoutDigest;
    }
}
