package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.onboarding.generate.GeneratedFile;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoLayout;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.DetectedStack;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackDetector;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Decides, per container in a settled {@link PocManifest}, what its Dockerfile needs: KEEP (it
 * exists and {@link DockerfileLinter} found nothing platform-breaking), REPLACE (it exists but
 * depends on a build step this platform does not run — {@code DOCKERFILE_EXTERNAL_ARTIFACT}), or
 * CREATE (it does not exist yet) — the last two rendered from a vetted template when
 * {@link StackDetector} recognizes the directory, or via {@link DockerfileDraftService} when it
 * does not, bounded by {@link DraftModelProperties#maxDockerfileModelCalls()} per generation run.
 *
 * <p>Every template-rendered CREATE/REPLACE also emits a matching {@code .dockerignore}, and, for
 * the nginx-served SPA stacks, the shared nginx config those templates {@code COPY} into the image
 * — neither is generated when the target already exists, which is reported instead as
 * {@code FILE_EXISTS_NOT_OVERWRITTEN} rather than silently skipped.
 *
 * <p>When an nginx-served frontend has exactly one sidecar and manifest edits are allowed (see
 * {@link #plan(PocManifest, RepoLayout, RepoFileReader, boolean)}), the nginx config also gets a
 * {@code /api/} reverse-proxy block wired to that sidecar via a {@code BACKEND_URL} env binding
 * this planner adds to the returned manifest — {@link PlanResult#manifest()} may therefore differ
 * from the manifest passed in, and the caller must use it instead of the original wherever the
 * result is meant to be written or validated.
 */
@Component
public class DockerfilePlanner {

    private static final Set<StackKind> NGINX_BASED_STACKS =
            Set.of(StackKind.ANGULAR_SPA, StackKind.VITE_SPA, StackKind.CRA_SPA, StackKind.STATIC_SITE);
    private static final int DEFAULT_CONTAINER_PORT = 8080;
    private static final int MAX_EVIDENCE_FILES_PER_CONTAINER = 10;
    private static final String BACKEND_URL_ENV_NAME = "BACKEND_URL";

    private final StackDetector stackDetector;
    private final DockerfileTemplates templates;
    private final DockerfileLinter linter;
    private final DockerfileDraftService draftService;
    private final DraftModelProperties properties;

    public DockerfilePlanner(StackDetector stackDetector, DockerfileTemplates templates, DockerfileLinter linter,
                              DockerfileDraftService draftService, DraftModelProperties properties) {
        this.stackDetector = stackDetector;
        this.templates = templates;
        this.linter = linter;
        this.draftService = draftService;
        this.properties = properties;
    }

    /** {@code manifest} carries any env additions this run made (currently just {@code BACKEND_URL}) — see the class javadoc. */
    public record PlanResult(PocManifest manifest, List<GeneratedFile> files, List<GenerationNotice> notices) {
    }

    /** Convenience for a manifest the caller will write/validate fresh — equivalent to passing {@code allowManifestEdits=true}. */
    public PlanResult plan(PocManifest manifest, RepoLayout layout, RepoFileReader fileReader) {
        return plan(manifest, layout, fileReader, true);
    }

    /**
     * @param allowManifestEdits false for a manifest that must never be rewritten (an existing
     *                           poc.yaml that already passed validation) — the reverse-proxy env
     *                           wiring is skipped entirely in that case, since it has nowhere safe
     *                           to be written; the nginx config generated is always the static-only
     *                           variant instead, regardless of whether a sidecar exists.
     */
    public PlanResult plan(PocManifest manifest, RepoLayout layout, RepoFileReader fileReader, boolean allowManifestEdits) {
        List<GeneratedFile> files = new ArrayList<>();
        List<GenerationNotice> notices = new ArrayList<>();
        Map<String, Map<String, String>> envAdditions = new LinkedHashMap<>();
        int modelCallsRemaining = properties.maxDockerfileModelCalls();

        for (ManifestContainer container : manifest.containers()) {
            modelCallsRemaining -= planOne(container, manifest.containers(), allowManifestEdits, layout, fileReader,
                    files, notices, envAdditions, modelCallsRemaining);
        }

        PocManifest updatedManifest = envAdditions.isEmpty() ? manifest : applyEnvAdditions(manifest, envAdditions);
        return new PlanResult(updatedManifest, files, notices);
    }

    private PocManifest applyEnvAdditions(PocManifest manifest, Map<String, Map<String, String>> envAdditions) {
        List<ManifestContainer> updated = manifest.containers().stream()
                .map(c -> withExtraEnv(c, envAdditions.get(c.name())))
                .toList();
        return new PocManifest(updated, manifest.resources(), manifest.scaling(), manifest.platform());
    }

    private ManifestContainer withExtraEnv(ManifestContainer container, Map<String, String> extra) {
        if (extra == null || extra.isEmpty()) {
            return container;
        }
        Map<String, String> mergedEnv = new LinkedHashMap<>(container.env());
        mergedEnv.putAll(extra);
        return new ManifestContainer(container.name(), container.role(), container.dockerfile(), container.context(),
                container.port(), mergedEnv, container.health(), container.repo(), container.requires());
    }

    /** Returns how many model calls this container spent, so the caller can decrement its shared budget. */
    private int planOne(ManifestContainer container, List<ManifestContainer> allContainers, boolean allowManifestEdits,
                         RepoLayout layout, RepoFileReader fileReader, List<GeneratedFile> files,
                         List<GenerationNotice> notices, Map<String, Map<String, String>> envAdditions, int modelCallsRemaining) {
        String path = container.dockerfile();
        int port = container.port() != null ? container.port() : DEFAULT_CONTAINER_PORT;
        String directory = directoryFor(container);

        String existing = fileReader.read(path).orElse(null);
        if (existing == null) {
            return createOrDraft(container, allContainers, allowManifestEdits, path, port, directory, layout, fileReader,
                    files, notices, envAdditions, modelCallsRemaining, GeneratedFile.Action.CREATE);
        }

        List<LintFinding> findings = linter.lint(existing, port);
        findings.forEach(f -> notices.add(toNotice(f, path, container.name())));
        boolean externalArtifact = findings.stream()
                .anyMatch(f -> f.code().equals(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT));
        if (!externalArtifact) {
            maybeNoticeMissingDockerignore(container, directory, layout, notices);
            return 0;
        }
        return createOrDraft(container, allContainers, allowManifestEdits, path, port, directory, layout, fileReader,
                files, notices, envAdditions, modelCallsRemaining, GeneratedFile.Action.REPLACE);
    }

    private int createOrDraft(ManifestContainer container, List<ManifestContainer> allContainers, boolean allowManifestEdits,
                               String path, int port, String directory, RepoLayout layout, RepoFileReader fileReader,
                               List<GeneratedFile> files, List<GenerationNotice> notices,
                               Map<String, Map<String, String>> envAdditions, int modelCallsRemaining, GeneratedFile.Action action) {
        DetectedStack detected = stackDetector.detect(fileReader, directory, layout.basenamesIn(directory));
        if (detected.kind() != StackKind.UNKNOWN) {
            GeneratedFile dockerfile = new GeneratedFile(path, GeneratedFile.Kind.DOCKERFILE, action,
                    GeneratedFile.Source.TEMPLATE, container.name(), templates.renderDockerfile(detected.kind(), detected.params()),
                    "Detected " + detected.kind() + " from " + String.join(", ", detected.evidencePaths()) + ".", false);
            files.add(dockerfile);
            addDockerignoreAndNginxConfig(detected.kind(), detected.params(), container, allContainers, allowManifestEdits,
                    directory, layout, fileReader, files, notices, envAdditions);
            return 0;
        }

        if (modelCallsRemaining <= 0) {
            notices.add(GenerationNotice.warning(GenerationNoticeCode.DOCKERFILE_UNRESOLVED,
                    "'" + container.name() + "' needs a Dockerfile this platform could not recognize a template for, "
                            + "and this run's budget for asking the draft model about one was already spent.")
                    .withContainer(container.name()).withPath(path));
            return 0;
        }

        Map<String, String> evidence = collectEvidence(directory, layout, fileReader);
        Optional<DockerfileDraftService.Result> result = draftService.draft(evidence, path, container.name(), port);
        if (result.isEmpty()) {
            notices.add(GenerationNotice.warning(GenerationNoticeCode.DOCKERFILE_UNRESOLVED,
                    "'" + container.name() + "' needs a Dockerfile, but no draft model is reachable right now.")
                    .withContainer(container.name()).withPath(path));
            return 1;
        }

        DockerfileDraftService.Result draft = result.get();
        notices.addAll(draft.notices());
        if (draft.dockerfile() != null) {
            GeneratedFile generated = draft.dockerfile();
            files.add(new GeneratedFile(generated.path(), generated.kind(), action, generated.source(),
                    generated.container(), generated.content(), generated.reason(), generated.needsReview()));
        }
        return 1;
    }

    private void addDockerignoreAndNginxConfig(StackKind kind, Map<String, String> params, ManifestContainer container,
                                                List<ManifestContainer> allContainers, boolean allowManifestEdits,
                                                String directory, RepoLayout layout, RepoFileReader fileReader,
                                                List<GeneratedFile> files, List<GenerationNotice> notices,
                                                Map<String, Map<String, String>> envAdditions) {
        String dockerignorePath = joinPath(directory, ".dockerignore");
        if (layout.directoriesWithDockerignore().contains(directory)) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.FILE_EXISTS_NOT_OVERWRITTEN,
                    "'" + dockerignorePath + "' already exists — left as-is.")
                    .withContainer(container.name()).withPath(dockerignorePath));
        } else {
            files.add(new GeneratedFile(dockerignorePath, GeneratedFile.Kind.DOCKERIGNORE, GeneratedFile.Action.CREATE,
                    GeneratedFile.Source.TEMPLATE, container.name(), templates.renderDockerignore(kind, params),
                    "Matches the generated Dockerfile.", false));
        }

        if (NGINX_BASED_STACKS.contains(kind)) {
            addNginxConfig(container, allContainers, allowManifestEdits, directory, fileReader, files, notices, envAdditions);
        }
    }

    /**
     * A frontend with exactly one sidecar gets a reverse-proxy nginx config wired to it (see the
     * class javadoc) — with more than one sidecar, which one is "the backend" is a genuine
     * ambiguity this platform will not guess at, so the static-only config is used and a notice
     * says why. {@code allowManifestEdits=false} skips the wiring outright, regardless of sidecar
     * count, since there would be nowhere safe to put the resulting {@code BACKEND_URL} binding.
     */
    private void addNginxConfig(ManifestContainer container, List<ManifestContainer> allContainers, boolean allowManifestEdits,
                                 String directory, RepoFileReader fileReader, List<GeneratedFile> files,
                                 List<GenerationNotice> notices, Map<String, Map<String, String>> envAdditions) {
        String nginxPath = joinPath(directory, "nginx-default.conf.template");
        if (fileReader.exists(nginxPath)) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.FILE_EXISTS_NOT_OVERWRITTEN,
                    "'" + nginxPath + "' already exists — left as-is.")
                    .withContainer(container.name()).withPath(nginxPath));
            return;
        }

        List<ManifestContainer> sidecars = allContainers.stream()
                .filter(c -> c.role() == ContainerRole.SIDECAR).toList();
        String backendName = allowManifestEdits && sidecars.size() == 1 ? sidecars.get(0).name() : null;

        if (backendName != null) {
            envAdditions.computeIfAbsent(container.name(), k -> new LinkedHashMap<>())
                    .put(BACKEND_URL_ENV_NAME, "${services." + backendName + ".url}");
            files.add(new GeneratedFile(nginxPath, GeneratedFile.Kind.CONFIG, GeneratedFile.Action.CREATE,
                    GeneratedFile.Source.TEMPLATE, container.name(), templates.renderNginxConfig(true),
                    "Serves the built frontend and proxies /api/ to the '" + backendName + "' sidecar.", false));
            notices.add(GenerationNotice.info(GenerationNoticeCode.REVERSE_PROXY_BACKEND_CHOSEN,
                    "'" + container.name() + "' proxies /api/ to the '" + backendName + "' sidecar, via a "
                            + BACKEND_URL_ENV_NAME + " env binding added to this container.")
                    .withContainer(container.name()));
            return;
        }

        files.add(new GeneratedFile(nginxPath, GeneratedFile.Kind.CONFIG, GeneratedFile.Action.CREATE,
                GeneratedFile.Source.TEMPLATE, container.name(), templates.renderNginxConfig(false),
                "The generated Dockerfile serves this build through nginx, using this config.", false));
        if (allowManifestEdits && sidecars.size() > 1) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.REVERSE_PROXY_BACKEND_CHOSEN,
                    "'" + container.name() + "' has more than one sidecar (" + sidecars.stream()
                            .map(ManifestContainer::name).collect(Collectors.joining(", ")) + ") — the platform "
                            + "could not tell which one to proxy /api/ to, so the generated nginx config only "
                            + "serves static files. Add a location block proxying to the right one's "
                            + "${services.<name>.url} by hand.")
                    .withContainer(container.name()));
        }
    }

    private void maybeNoticeMissingDockerignore(ManifestContainer container, String directory, RepoLayout layout,
                                                 List<GenerationNotice> notices) {
        if (!layout.directoriesWithDockerignore().contains(directory)) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.DOCKERFILE_NO_DOCKERIGNORE,
                    "This container's Dockerfile has no .dockerignore alongside it.").withContainer(container.name()));
        }
    }

    private Map<String, String> collectEvidence(String directory, RepoLayout layout, RepoFileReader fileReader) {
        Map<String, String> evidence = new LinkedHashMap<>();
        for (String basename : layout.basenamesIn(directory)) {
            if (evidence.size() >= MAX_EVIDENCE_FILES_PER_CONTAINER) {
                break;
            }
            String path = joinPath(directory, basename);
            fileReader.read(path).ifPresent(content -> evidence.put(path, content));
        }
        return evidence;
    }

    private GenerationNotice toNotice(LintFinding finding, String path, String containerName) {
        GenerationNotice notice = switch (finding.severity()) {
            case ERROR -> GenerationNotice.error(finding.code(), finding.message());
            case WARNING -> GenerationNotice.warning(finding.code(), finding.message());
            case INFO -> GenerationNotice.info(finding.code(), finding.message());
        };
        return notice.withPath(path).withContainer(containerName);
    }

    private String directoryFor(ManifestContainer container) {
        String context = container.context();
        return context == null || context.isBlank() || context.equals(".") ? "" : context;
    }

    private String joinPath(String directory, String basename) {
        return directory.isEmpty() ? basename : directory + "/" + basename;
    }
}
