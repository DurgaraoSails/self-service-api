package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoInventory.EvidenceFile;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ModelRequest;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EvidenceRedactor;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.SecretHeuristics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Asks a {@link ManifestDraftModel} for structured JSON describing a repository's containers —
 * never for YAML text, and manifest-only as of Phase 3: Dockerfiles are
 * {@code DockerfilePlanner}/{@code DockerfileDraftService}'s job, decided separately per container
 * once the container plan itself is settled. Java renders the YAML ({@link ManifestYamlWriter});
 * the model's only job is to decide facts about the repository, which a schema-constrained JSON
 * object is far more reliable at than well-formed YAML would be, especially from a modest local model.
 *
 * <p>Every draft is run through the real {@link ManifestValidator} before it is returned — on
 * violations, they are sent back verbatim as part of a corrective follow-up prompt, at most twice.
 * The caller's {@code overlay} (imported cloudbuild.yaml settings, via {@code ManifestMerger}) is
 * applied before every validation, not just the last — one repair loop covers both what the model
 * got wrong and what the import demands, since a violation could come from either. A draft that
 * still fails becomes a {@link ManifestDraftValidationException}, never a manifest that did not
 * pass the validator every other path in this platform trusts.
 */
@Service
public class ManifestDraftService {

    /** Initial attempt plus at most two repairs. */
    static final int MAX_ATTEMPTS = 3;

    private static final String SYSTEM_PROMPT = """
            You design poc.yaml deployment manifests for a platform that runs repositories as \
            Cloud Run services with one or more containers. You are given FACTS the platform already
            established by reading the repository deterministically, plus a bounded set of evidence \
            files, and must respond with ONLY a JSON object matching the supplied schema — no prose, \
            no markdown fences.

            Rules:
            - FACTS are authoritative. If FACTS says a directory has no Dockerfile, "dockerfile" may \
            still name a path that does not exist yet — prefer "<context>/Dockerfile" in that case. \
            If FACTS says a Dockerfile already exists at a path, use that exact path.
            - Exactly one container must have role "ingress"; every other container is "sidecar". \
            When more than one container could plausibly be it, prefer the one that serves the \
            browser frontend directly (a static/SPA build, or a server that renders/serves HTML to \
            a browser) over a pure backend API or worker service — unless FACTS or the evidence \
            clearly shows the repository is meant to work the other way around.
            - "dockerfile" and "context" are both paths from the repository root, independent of \
            each other — "dockerfile" is never resolved relative to "context".
            - A sidecar container must declare "port". Sidecar ports must all be distinct from each \
            other and from the ingress port, starting at 8081 if you are choosing freely.
            - Set "health" only when the evidence actually shows a health/readiness endpoint — do \
            not invent one.
            - Never invent a container that has no supporting evidence in the files or FACTS you were shown.
            - Evidence containing the literal text "<redacted>" marks a value the platform already \
            removed because it looked like a credential. Never copy "<redacted>" itself into your \
            response — if a container needs that variable, declare it under "requires" as \
            {"name": "<ENV_VAR_NAME>", "secret": true}, name only.
            - NEVER write the VALUE of any credential, API key, password, or token you see in the \
            evidence into your response, under any field. Plain (non-secret) environment variables \
            that are not credentials go under "env".
            - "evidence" on each container is one short sentence citing the file(s) that justified \
            your choice of role/port/health for that container.
            - "assumptions" lists anything you inferred rather than read directly, in plain English, \
            one per array entry.
            - If asked to correct a previous draft, fix every violation listed while preserving \
            everything about the previous draft that was not named as a problem.
            """;

    /**
     * {@code port}/{@code health} are deliberately plain, single-typed schemas rather than JSON
     * Schema's {@code {"type": ["integer","null"]}} union form. Vertex's {@code responseSchema} is
     * a proto-backed {@code Schema} whose {@code type} field is a single scalar, not a repeated
     * one, and rejects an array there outright ("Proto field is not repeating, cannot start list") —
     * confirmed by a real 400 from Vertex naming exactly these two fields by index. Neither is in
     * {@code required} below, so the model omits the field entirely to mean "no value" instead of
     * writing an explicit null — which every provider's schema dialect agrees on, unlike nullability.
     */
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "required": ["containers", "assumptions"],
              "properties": {
                "containers": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "required": ["name", "role", "dockerfile", "context"],
                    "properties": {
                      "name": {"type": "string"},
                      "role": {"type": "string", "enum": ["ingress", "sidecar"]},
                      "dockerfile": {"type": "string"},
                      "context": {"type": "string"},
                      "port": {"type": "integer"},
                      "health": {"type": "string"},
                      "env": {"type": "object", "additionalProperties": {"type": "string"}},
                      "requires": {
                        "type": "array",
                        "items": {
                          "type": "object",
                          "required": ["name", "secret"],
                          "properties": {
                            "name": {"type": "string"},
                            "secret": {"type": "boolean"}
                          }
                        }
                      },
                      "evidence": {"type": "string"}
                    }
                  }
                },
                "assumptions": {"type": "array", "items": {"type": "string"}}
              }
            }
            """;

    private final List<ManifestDraftModel> models;
    private final DraftModelProperties properties;
    private final ManifestValidator validator;
    private final ObjectMapper objectMapper;

    public ManifestDraftService(List<ManifestDraftModel> models, DraftModelProperties properties,
                                 ManifestValidator validator, ObjectMapper objectMapper) {
        this.models = models;
        this.properties = properties;
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    /** The adapter selected by {@code poc-generator.provider}, or empty if none is registered under that name. */
    public Optional<ManifestDraftModel> selectedModel() {
        return models.stream().filter(m -> m.name().equals(properties.provider())).findFirst();
    }

    /**
     * @param facts                 what the platform already established deterministically —
     *                              authoritative, per the system prompt.
     * @param existingManifestYaml  the repo's current poc.yaml, when this is a correction rather
     *                              than a fresh draft — included in the prompt so the model
     *                              corrects it rather than starting over. Null for a fresh draft.
     * @param overlay               applied to the parsed manifest before every validation —
     *                              {@code ManifestMerger}'s import overlay, or
     *                              {@link UnaryOperator#identity()} when there is nothing to
     *                              overlay. Running it every attempt, not just the last, means one
     *                              repair loop covers both what the model got wrong and what the
     *                              overlay itself demands.
     */
    public ManifestDraftResult draft(RepoInventory inventory, ManifestFacts facts, String existingManifestYaml,
                                      UnaryOperator<PocManifest> overlay) {
        ManifestDraftModel model = selectedModel()
                .orElseThrow(() -> new IllegalStateException(
                        "No ManifestDraftModel registered for poc-generator.provider='" + properties.provider() + "'"));

        List<GenerationNotice> notices = findRedactedSecrets(inventory);
        List<String> violations = List.of();
        String userPrompt = buildUserPrompt(inventory, facts, existingManifestYaml, null);

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String json = model.draft(new ModelRequest(SYSTEM_PROMPT, userPrompt, JSON_SCHEMA, properties.timeout()));
            DraftResponse response;
            try {
                response = objectMapper.readValue(json, DraftResponse.class);
            } catch (RuntimeException e) {
                // A schema-constrained model can still return text that doesn't parse (truncated
                // output the adapters' own guards missed, a stray markdown fence, etc.) — this
                // counts as a failed attempt with a repair message, never an uncaught 500. Jackson
                // 3's exceptions are already unchecked, so nothing has to declare this.
                violations = List.of("the response was not valid JSON matching the schema: " + e.getMessage());
                userPrompt = buildUserPrompt(inventory, facts, existingManifestYaml, violations);
                continue;
            }
            PocManifest manifest = overlay.apply(toManifest(response));
            violations = validator.validate(manifest);
            if (violations.isEmpty()) {
                notices.addAll(uncertainSecretNotices(manifest));
                return new ManifestDraftResult(manifest, safeList(response.assumptions()), notices);
            }
            userPrompt = buildUserPrompt(inventory, facts, existingManifestYaml, violations);
        }
        throw new ManifestDraftValidationException(violations);
    }

    private String buildUserPrompt(RepoInventory inventory, ManifestFacts facts, String existingManifestYaml,
                                    List<String> priorViolations) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("FACTS (authoritative — see the rules above):\n").append(objectMapper.writeValueAsString(facts)).append('\n');

        prompt.append("\nRepository file paths (not all shown in full below):\n");
        inventory.tree().entries().stream().filter(e -> e.isBlob()).limit(200)
                .forEach(entry -> prompt.append("- ").append(entry.path()).append('\n'));
        if (inventory.treeTruncated()) {
            prompt.append("(the file list above is truncated — this repository is larger than could be listed)\n");
        }

        prompt.append("\nFile contents (credential-shaped values already redacted):\n");
        for (EvidenceFile file : inventory.evidenceFiles()) {
            prompt.append("--- ").append(file.path()).append(" ---\n")
                    .append(EvidenceRedactor.redact(file.content())).append("\n\n");
        }

        if (existingManifestYaml != null && !existingManifestYaml.isBlank()) {
            prompt.append("\nThis repository already has a poc.yaml, which the platform's validator rejected. "
                    + "Correct it rather than designing a new one from scratch:\n").append(existingManifestYaml).append('\n');
        }

        if (priorViolations != null && !priorViolations.isEmpty()) {
            prompt.append("\nYour previous draft failed validation with these problems — fix every one:\n");
            priorViolations.forEach(v -> prompt.append("- ").append(v).append('\n'));
        }
        return prompt.toString();
    }

    private List<GenerationNotice> findRedactedSecrets(RepoInventory inventory) {
        List<GenerationNotice> notices = new ArrayList<>();
        for (EvidenceFile file : inventory.evidenceFiles()) {
            if (SecretHeuristics.containsSecretAssignment(file.content())) {
                notices.add(GenerationNotice.warning(GenerationNoticeCode.EVIDENCE_SECRET_REDACTED,
                        "'" + file.path() + "' appears to contain a committed credential — rotate/remove it. "
                                + "It was redacted before reaching the draft model and was not copied into the "
                                + "generated manifest.").withPath(file.path()));
            }
        }
        return notices;
    }

    /**
     * The model's own {@code secret: true} judgment goes straight into the manifest with no
     * deterministic check — unlike the cloudbuild-import path, which runs every requirement through
     * {@code EnvVarClassifier}/{@link SecretHeuristics}. Forcibly reclassifying a requirement the
     * model marked secret would risk leaking a real one whose name just doesn't match the heuristic,
     * so this only flags the mismatch for a human to double-check; the entry itself is untouched.
     */
    private List<GenerationNotice> uncertainSecretNotices(PocManifest manifest) {
        List<GenerationNotice> notices = new ArrayList<>();
        for (ManifestContainer container : manifest.containers()) {
            for (ManifestRequirement requirement : container.requires()) {
                if (requirement.secret() && !SecretHeuristics.nameLooksSecret(requirement.name())) {
                    notices.add(GenerationNotice.info(GenerationNoticeCode.SECRET_CLASSIFICATION_UNCERTAIN,
                            "'" + requirement.name() + "' was drafted as a secret (requires: secret: true), but "
                                    + "its name doesn't look credential-shaped — double check it's really meant to "
                                    + "come from Secret Manager rather than a plain, committable env value.")
                            .withContainer(container.name()));
                }
            }
        }
        return notices;
    }

    private PocManifest toManifest(DraftResponse response) {
        List<ManifestContainer> containers = safeList(response.containers()).stream()
                .map(this::toContainer)
                .toList();
        return new PocManifest(containers, new Resources(null, null));
    }

    private ManifestContainer toContainer(DraftContainer draft) {
        String dockerfile = blankToDefault(draft.dockerfile(), "Dockerfile");
        String context = blankToDefault(draft.context(), ".");
        ContainerRole role = parseRole(draft.role());
        Map<String, String> env = draft.env() == null ? Map.of() : new LinkedHashMap<>(draft.env());
        List<ManifestRequirement> requires = safeList(draft.requires()).stream()
                .map(r -> new ManifestRequirement(r.name(), Boolean.TRUE.equals(r.secret())))
                .toList();
        return new ManifestContainer(draft.name(), role, dockerfile, context, draft.port(), env,
                blankToNull(draft.health()), null, requires);
    }

    private ContainerRole parseRole(String role) {
        try {
            return ContainerRole.valueOf(role == null ? "" : role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Feeds straight into a validator violation ("none was found" / role mismatch) rather
            // than failing the whole attempt — the repair loop then tells the model exactly that.
            return ContainerRole.SIDECAR;
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? List.of() : list;
    }

    // --- the model's JSON response shape, mapped by hand like every other parser in this codebase --

    private record DraftResponse(List<DraftContainer> containers, List<String> assumptions) {
    }

    private record DraftContainer(String name, String role, String dockerfile, String context, Integer port,
                                   String health, Map<String, String> env, List<DraftRequirement> requires,
                                   String evidence) {
    }

    private record DraftRequirement(String name, Boolean secret) {
    }
}
