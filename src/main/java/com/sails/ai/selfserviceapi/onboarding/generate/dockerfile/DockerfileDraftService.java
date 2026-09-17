package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import com.sails.ai.selfserviceapi.onboarding.generate.GeneratedFile;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftException;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ModelRequest;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EvidenceRedactor;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * One model call per container whose stack {@code StackDetector} could not recognize — the
 * fallback behind {@code DockerfilePlanner}'s CREATE-via-model path. Sends only that one
 * container's own redacted evidence, never the whole repository.
 *
 * <p>The model may still recognize a stack {@code StackDetector}'s marker rules missed; when it
 * does, its answer is rendered from the same vetted template every deterministic detection uses
 * (source TEMPLATE) rather than trusted as raw Dockerfile text. Only genuinely {@code OTHER} stacks
 * get the model's own Dockerfile — linted, with one repair attempt on an ERROR finding, source
 * MODEL and always {@code needsReview}.
 */
@Service
public class DockerfileDraftService {

    /** Initial attempt plus one repair — cheaper than the manifest's own budget since this runs once per unrecognized container. */
    static final int MAX_ATTEMPTS = 2;

    private static final String SYSTEM_PROMPT = """
            You write a Dockerfile for one component of a repository, to be deployed on Cloud Run. \
            You are given that component's own files and must respond with ONLY a JSON object \
            matching the supplied schema — no prose, no markdown fences.

            First, try to recognize the stack from this list: %s. If you recognize one, set "stack" \
            to its exact name and fill "params" with whatever that stack's template needs (consult \
            the file contents you were given — package.json engines/scripts, a lockfile's presence, \
            a Python entrypoint, and so on); leave "dockerfile" empty in that case.

            If you cannot recognize any of those stacks, set "stack" to "OTHER" and write the \
            Dockerfile yourself in "dockerfile". It must: use multiple stages if it builds anything; \
            listen on the $PORT environment variable (never a hardcoded port) on 0.0.0.0, never \
            127.0.0.1 or localhost; run its final stage as a non-root user; never COPY a .env file, \
            a *.pem file, or any file whose name suggests a credential; never hardcode a secret \
            value as an ENV/ARG default; include EXPOSE 8080.

            Evidence containing the literal text "<redacted>" marks a value already removed because \
            it looked like a credential — never write it, or any other credential value you see, \
            into the Dockerfile.

            "reason" explains what you detected and why. "assumptions" lists anything you inferred \
            rather than read directly.

            If asked to fix a previous Dockerfile, fix every problem listed while preserving \
            everything else about it.
            """;

    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["stack", "dockerfile", "reason", "assumptions"],
              "properties": {
                "stack": {"type": "string"},
                "params": {"type": "object", "additionalProperties": {"type": "string"}},
                "dockerfile": {"type": "string"},
                "reason": {"type": "string"},
                "assumptions": {"type": "array", "items": {"type": "string"}}
              }
            }
            """;

    private final List<ManifestDraftModel> models;
    private final DraftModelProperties properties;
    private final DockerfileTemplates templates;
    private final DockerfileLinter linter;
    private final ObjectMapper objectMapper;

    public DockerfileDraftService(List<ManifestDraftModel> models, DraftModelProperties properties,
                                   DockerfileTemplates templates, DockerfileLinter linter, ObjectMapper objectMapper) {
        this.models = models;
        this.properties = properties;
        this.templates = templates;
        this.linter = linter;
        this.objectMapper = objectMapper;
    }

    public record Result(GeneratedFile dockerfile, List<GenerationNotice> notices) {
    }

    /**
     * @param evidence   this one container's own files, path to (unredacted) content — redacted
     *                   here, immediately before it reaches the prompt.
     * @param path       where the generated Dockerfile belongs, e.g. {@code apps/api/Dockerfile}.
     */
    public Optional<Result> draft(Map<String, String> evidence, String path, String containerName, int containerPort) {
        Optional<ManifestDraftModel> model = models.stream().filter(m -> m.name().equals(properties.provider())).findFirst();
        if (model.isEmpty() || !model.get().isAvailable()) {
            return Optional.empty();
        }

        String userPrompt = buildUserPrompt(evidence, null);
        String systemPrompt = SYSTEM_PROMPT.formatted(String.join(", ", stackNames()));
        List<LintFinding> lastFindings = List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String json;
            try {
                json = model.get().draft(new ModelRequest(systemPrompt, userPrompt, JSON_SCHEMA, properties.timeout()));
            } catch (ManifestDraftException e) {
                return Optional.empty();
            }

            DraftResponse response;
            try {
                response = objectMapper.readValue(json, DraftResponse.class);
            } catch (RuntimeException e) {
                return Optional.empty();
            }

            StackKind stack = parseStack(response.stack());
            if (stack != null) {
                return Optional.of(renderFromTemplate(stack, response, path, containerName));
            }

            String dockerfile = response.dockerfile();
            if (dockerfile == null || dockerfile.isBlank()) {
                return Optional.empty();
            }
            List<LintFinding> findings = linter.lint(dockerfile, containerPort);
            boolean hasError = findings.stream().anyMatch(f -> f.severity() == LintFinding.Severity.ERROR);
            if (!hasError) {
                return Optional.of(modelResult(dockerfile, response, path, containerName, findings));
            }
            lastFindings = findings;
            userPrompt = buildUserPrompt(evidence, findings);
        }

        return Optional.of(new Result(null, List.of(GenerationNotice.warning(GenerationNoticeCode.DOCKERFILE_UNRESOLVED,
                "The draft model could not produce a working Dockerfile for '" + containerName + "' after "
                        + MAX_ATTEMPTS + " attempts (" + summarize(lastFindings) + ").").withContainer(containerName))));
    }

    private Result renderFromTemplate(StackKind stack, DraftResponse response, String path, String containerName) {
        Map<String, String> params = response.params() == null ? Map.of() : response.params();
        String dockerfile;
        try {
            dockerfile = templates.renderDockerfile(stack, params);
        } catch (IllegalStateException e) {
            // The model claimed a recognized stack but didn't supply everything the template
            // needs — fail safe to "needs a human", never a half-rendered file with {{ in it.
            return new Result(null, List.of(GenerationNotice.warning(GenerationNoticeCode.DOCKERFILE_UNRESOLVED,
                    "The draft model recognized '" + stack + "' for '" + containerName + "' but did not supply "
                            + "everything its template needs (" + e.getMessage() + ").").withContainer(containerName)));
        }
        GeneratedFile file = new GeneratedFile(path, GeneratedFile.Kind.DOCKERFILE, GeneratedFile.Action.CREATE,
                GeneratedFile.Source.TEMPLATE, containerName, dockerfile, safe(response.reason()), true);
        return new Result(file, List.of());
    }

    private Result modelResult(String dockerfile, DraftResponse response, String path, String containerName, List<LintFinding> findings) {
        GeneratedFile file = new GeneratedFile(path, GeneratedFile.Kind.DOCKERFILE, GeneratedFile.Action.CREATE,
                GeneratedFile.Source.MODEL, containerName, dockerfile, safe(response.reason()), true);
        List<GenerationNotice> notices = findings.stream()
                .map(f -> toNotice(f, path, containerName))
                .toList();
        return new Result(file, notices);
    }

    private GenerationNotice toNotice(LintFinding finding, String path, String containerName) {
        GenerationNotice notice = switch (finding.severity()) {
            case ERROR -> GenerationNotice.error(finding.code(), finding.message());
            case WARNING -> GenerationNotice.warning(finding.code(), finding.message());
            case INFO -> GenerationNotice.info(finding.code(), finding.message());
        };
        return notice.withPath(path).withContainer(containerName);
    }

    private String buildUserPrompt(Map<String, String> evidence, List<LintFinding> priorFindings) {
        StringBuilder prompt = new StringBuilder();
        evidence.forEach((filePath, content) ->
                prompt.append("--- ").append(filePath).append(" ---\n").append(EvidenceRedactor.redact(content)).append("\n\n"));
        if (priorFindings != null && !priorFindings.isEmpty()) {
            prompt.append("Your previous Dockerfile had these problems — fix every one:\n");
            priorFindings.forEach(f -> prompt.append("- ").append(f.message()).append('\n'));
        }
        return prompt.toString();
    }

    private List<String> stackNames() {
        List<String> names = new ArrayList<>();
        for (StackKind kind : StackKind.values()) {
            if (kind != StackKind.UNKNOWN) {
                names.add(kind.name());
            }
        }
        return names;
    }

    /** Null means "OTHER" (or an unparseable answer, treated the same way) — the raw-Dockerfile path. */
    private StackKind parseStack(String raw) {
        if (raw == null) {
            return null;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        if (upper.equals("OTHER") || upper.equals("UNKNOWN")) {
            return null;
        }
        try {
            return StackKind.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String summarize(List<LintFinding> findings) {
        return findings.stream().filter(f -> f.severity() == LintFinding.Severity.ERROR)
                .map(LintFinding::message).findFirst().orElse("no details");
    }

    private record DraftResponse(String stack, Map<String, String> params, String dockerfile, String reason,
                                  List<String> assumptions) {
    }
}
