package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

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
 */
@Component
public class DockerfilePlanner {

    private static final Set<StackKind> NGINX_BASED_STACKS =
            Set.of(StackKind.ANGULAR_SPA, StackKind.VITE_SPA, StackKind.CRA_SPA, StackKind.STATIC_SITE);
    private static final int DEFAULT_CONTAINER_PORT = 8080;
    private static final int MAX_EVIDENCE_FILES_PER_CONTAINER = 10;

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

    public record PlanResult(List<GeneratedFile> files, List<GenerationNotice> notices) {
    }

    public PlanResult plan(PocManifest manifest, RepoLayout layout, RepoFileReader fileReader) {
        List<GeneratedFile> files = new ArrayList<>();
        List<GenerationNotice> notices = new ArrayList<>();
        int modelCallsRemaining = properties.maxDockerfileModelCalls();

        for (ManifestContainer container : manifest.containers()) {
            modelCallsRemaining -= planOne(container, layout, fileReader, files, notices, modelCallsRemaining);
        }
        return new PlanResult(files, notices);
    }

    /** Returns how many model calls this container spent, so the caller can decrement its shared budget. */
    private int planOne(ManifestContainer container, RepoLayout layout, RepoFileReader fileReader,
                         List<GeneratedFile> files, List<GenerationNotice> notices, int modelCallsRemaining) {
        String path = container.dockerfile();
        int port = container.port() != null ? container.port() : DEFAULT_CONTAINER_PORT;
        String directory = directoryFor(container);

        String existing = fileReader.read(path).orElse(null);
        if (existing == null) {
            return createOrDraft(container, path, port, directory, layout, fileReader, files, notices,
                    modelCallsRemaining, GeneratedFile.Action.CREATE);
        }

        List<LintFinding> findings = linter.lint(existing, port);
        findings.forEach(f -> notices.add(toNotice(f, path, container.name())));
        boolean externalArtifact = findings.stream()
                .anyMatch(f -> f.code().equals(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT));
        if (!externalArtifact) {
            maybeNoticeMissingDockerignore(container, directory, layout, notices);
            return 0;
        }
        return createOrDraft(container, path, port, directory, layout, fileReader, files, notices,
                modelCallsRemaining, GeneratedFile.Action.REPLACE);
    }

    private int createOrDraft(ManifestContainer container, String path, int port, String directory, RepoLayout layout,
                               RepoFileReader fileReader, List<GeneratedFile> files, List<GenerationNotice> notices,
                               int modelCallsRemaining, GeneratedFile.Action action) {
        DetectedStack detected = stackDetector.detect(fileReader, directory, layout.basenamesIn(directory));
        if (detected.kind() != StackKind.UNKNOWN) {
            GeneratedFile dockerfile = new GeneratedFile(path, GeneratedFile.Kind.DOCKERFILE, action,
                    GeneratedFile.Source.TEMPLATE, container.name(), templates.renderDockerfile(detected.kind(), detected.params()),
                    "Detected " + detected.kind() + " from " + String.join(", ", detected.evidencePaths()) + ".", false);
            files.add(dockerfile);
            addDockerignoreAndNginxConfig(detected.kind(), detected.params(), container, directory, layout, fileReader, files, notices);
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
                                                String directory, RepoLayout layout, RepoFileReader fileReader,
                                                List<GeneratedFile> files, List<GenerationNotice> notices) {
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
            String nginxPath = joinPath(directory, "nginx-default.conf.template");
            if (fileReader.exists(nginxPath)) {
                notices.add(GenerationNotice.info(GenerationNoticeCode.FILE_EXISTS_NOT_OVERWRITTEN,
                        "'" + nginxPath + "' already exists — left as-is.")
                        .withContainer(container.name()).withPath(nginxPath));
            } else {
                files.add(new GeneratedFile(nginxPath, GeneratedFile.Kind.CONFIG, GeneratedFile.Action.CREATE,
                        GeneratedFile.Source.TEMPLATE, container.name(), templates.renderNginxConfig(),
                        "The generated Dockerfile serves this build through nginx, using this config.", false));
            }
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
