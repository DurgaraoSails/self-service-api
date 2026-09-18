package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoInventory.EvidenceFile;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ModelRequest;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The repair loop is the part of this pipeline that cannot be exercised without a real model
 * running — so it is tested here against a stub {@link ManifestDraftModel} instead. This is the
 * whole reason {@code ManifestDraftModel} is a port: the pipeline is fully testable with no model
 * running at all.
 */
class ManifestDraftServiceTest {

    private static final ManifestValidator VALIDATOR =
            new ManifestValidator(new ManifestProperties(null, null, 0), new PocRuntimeProperties(null, null, null));

    private static final ManifestFacts NO_FACTS = ManifestFacts.of(List.of());

    private static final String VALID_JSON = """
            {"containers":[{"name":"app","role":"ingress","dockerfile":"Dockerfile","context":".",
            "evidence":"root Dockerfile"}],"assumptions":["Single service."]}
            """;

    /** No ingress container — ManifestValidator rejects this. */
    private static final String INVALID_JSON = """
            {"containers":[{"name":"app","role":"sidecar","dockerfile":"Dockerfile","context":".",
            "port":8080,"evidence":"root Dockerfile"}],"assumptions":[]}
            """;

    private static RepoInventory fixtureInventory() {
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("Dockerfile", "blob", 40L)), false);
        return new RepoInventory(tree, List.of(new EvidenceFile("Dockerfile", "FROM node:20\nEXPOSE 3000\n")), false);
    }

    private ManifestDraftService serviceWithModel(ManifestDraftModel model) {
        DraftModelProperties properties = new DraftModelProperties(true, model.name(), Duration.ofSeconds(30), null, null, null);
        return new ManifestDraftService(List.of(model), properties, VALIDATOR, JsonMapper.builder().build());
    }

    private ManifestDraftResult draft(ManifestDraftService service, RepoInventory inventory) {
        return service.draft(inventory, NO_FACTS, null, UnaryOperator.identity());
    }

    @Test
    void aValidFirstDraftNeedsNoRepair() {
        List<String> promptsSeen = new ArrayList<>();
        ManifestDraftModel model = scripted("stub", promptsSeen, VALID_JSON);

        ManifestDraftResult result = draft(serviceWithModel(model), fixtureInventory());

        assertThat(result.manifest().ingress().name()).isEqualTo("app");
        assertThat(promptsSeen).hasSize(1);
    }

    /**
     * A first draft that fails validation gets exactly one more chance with the violations fed
     * back verbatim; a valid second draft succeeds without exhausting the full repair budget.
     */
    @Test
    void aFailedFirstDraftIsRepairedWithTheValidatorsOwnViolations() {
        List<String> promptsSeen = new ArrayList<>();
        ManifestDraftModel model = scripted("stub", promptsSeen, INVALID_JSON, VALID_JSON);

        ManifestDraftResult result = draft(serviceWithModel(model), fixtureInventory());

        assertThat(result.manifest().ingress().name()).isEqualTo("app");
        assertThat(promptsSeen).hasSize(2);
        assertThat(promptsSeen.get(1))
                .contains("exactly one container must have role 'ingress'");
    }

    /** Three strikes: the pipeline gives up rather than looping, or returning a manifest that never validated. */
    @Test
    void exhaustingEveryRepairAttemptFailsRatherThanLoopingForever() {
        List<String> promptsSeen = new ArrayList<>();
        ManifestDraftModel model = scripted("stub", promptsSeen, INVALID_JSON, INVALID_JSON, INVALID_JSON);

        assertThatThrownBy(() -> draft(serviceWithModel(model), fixtureInventory()))
                .isInstanceOf(ManifestDraftValidationException.class)
                .satisfies(e -> assertThat(((ManifestDraftValidationException) e).violations())
                        .anyMatch(v -> v.contains("ingress")));
        assertThat(promptsSeen).hasSize(3);
    }

    /**
     * A schema-constrained model can still return text that doesn't parse (a stray markdown fence,
     * truncated output the adapters' own guards missed). This must count as a failed attempt with a
     * repair message, never an uncaught 500 — the whole point of running every draft through a
     * bounded repair loop rather than trusting the first response.
     */
    @Test
    void malformedJsonIsAFailedAttemptNotAnUncaught500() {
        List<String> promptsSeen = new ArrayList<>();
        ManifestDraftModel model = scripted("stub", promptsSeen, "not json at all {{{", VALID_JSON);

        ManifestDraftResult result = draft(serviceWithModel(model), fixtureInventory());

        assertThat(result.manifest().ingress().name()).isEqualTo("app");
        assertThat(promptsSeen).hasSize(2);
        assertThat(promptsSeen.get(1)).contains("not valid JSON");
    }

    /** Three malformed responses in a row must still give up rather than loop forever or throw an unexpected type. */
    @Test
    void exhaustingEveryAttemptOnMalformedJsonFailsAsAValidationException() {
        ManifestDraftModel model = scripted("stub", new ArrayList<>(),
                "not json", "still not json", "definitely not json");

        assertThatThrownBy(() -> draft(serviceWithModel(model), fixtureInventory()))
                .isInstanceOf(ManifestDraftValidationException.class);
    }

    @Test
    void secretLiteralsInEvidenceBecomeNoticesNeverManifestValues() {
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry(".env", "blob", 40L)), false);
        RepoInventory inventory = new RepoInventory(tree,
                List.of(new EvidenceFile(".env", "OPENAI_API_KEY=sk-verylongsecretvalue1234567890")), false);
        ManifestDraftModel model = scripted("stub", new ArrayList<>(), VALID_JSON);

        ManifestDraftResult result = draft(serviceWithModel(model), inventory);

        assertThat(result.notices()).anyMatch(n -> ".env".equals(n.path()));
        assertThat(result.manifest().ingress().env()).doesNotContainKey("OPENAI_API_KEY");
    }

    /**
     * The model's own secret/env judgment isn't second-guessed — reclassifying a real secret to
     * plain env: because its name doesn't match a heuristic would leak it. It is only flagged for a
     * human to double-check, and the requirement itself is left exactly as drafted.
     */
    @Test
    void aModelDraftedSecretWithAnUnconvincingNameIsFlaggedNotReclassified() {
        String jsonWithOddlyNamedSecret = """
                {"containers":[{"name":"app","role":"ingress","dockerfile":"Dockerfile","context":".",
                "requires":[{"name":"APP_MODE","secret":true}],"evidence":"root Dockerfile"}],"assumptions":[]}
                """;
        ManifestDraftModel model = scripted("stub", new ArrayList<>(), jsonWithOddlyNamedSecret);

        ManifestDraftResult result = draft(serviceWithModel(model), fixtureInventory());

        assertThat(result.notices()).anyMatch(n -> GenerationNoticeCode.SECRET_CLASSIFICATION_UNCERTAIN.equals(n.code())
                && n.message().contains("APP_MODE"));
        assertThat(result.manifest().ingress().requires()).anyMatch(r -> r.name().equals("APP_MODE") && r.secret());
    }

    /** A name that already looks credential-shaped needs no second-guessing. */
    @Test
    void aModelDraftedSecretWithAConvincingNameIsNotFlagged() {
        String jsonWithObviousSecret = """
                {"containers":[{"name":"app","role":"ingress","dockerfile":"Dockerfile","context":".",
                "requires":[{"name":"DB_PASSWORD","secret":true}],"evidence":"root Dockerfile"}],"assumptions":[]}
                """;
        ManifestDraftModel model = scripted("stub", new ArrayList<>(), jsonWithObviousSecret);

        ManifestDraftResult result = draft(serviceWithModel(model), fixtureInventory());

        assertThat(result.notices()).noneMatch(n -> GenerationNoticeCode.SECRET_CLASSIFICATION_UNCERTAIN.equals(n.code()));
    }

    /** The FACTS block is always the first thing the model sees — authoritative per the system prompt. */
    @Test
    void theFactsBlockIsAlwaysIncludedInThePrompt() {
        List<String> promptsSeen = new ArrayList<>();
        ManifestDraftModel model = scripted("stub", promptsSeen, VALID_JSON);
        ManifestFacts facts = ManifestFacts.of(List.of(
                new ManifestFacts.ComponentFact("", com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind.NODE_SERVER, true, "Dockerfile")));

        serviceWithModel(model).draft(fixtureInventory(), facts, null, UnaryOperator.identity());

        assertThat(promptsSeen).singleElement().satisfies(prompt ->
                assertThat(prompt).contains("FACTS").contains("NODE_SERVER"));
    }

    /**
     * Vertex's {@code responseSchema} is a proto-backed {@code Schema} whose {@code type} field is
     * a single scalar, not a repeated one — it rejects JSON Schema's {@code {"type": ["integer",
     * "null"]}} union form outright with a 400 ("Proto field is not repeating, cannot start list"),
     * confirmed against a real Vertex call. Regression guard: the one schema shared by every
     * provider must never reintroduce a type array.
     */
    @Test
    void theSchemaNeverUsesAJsonSchemaTypeArrayVertexCannotAccept() {
        List<String> schemasSeen = new ArrayList<>();
        ManifestDraftModel model = new ManifestDraftModel() {
            @Override
            public String name() {
                return "stub";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String draft(ModelRequest request) {
                schemasSeen.add(request.jsonSchema());
                return VALID_JSON;
            }
        };

        draft(serviceWithModel(model), fixtureInventory());

        assertThat(schemasSeen).singleElement().satisfies(schema ->
                assertThat(schema).doesNotContain("\"type\": [").doesNotContain("\"type\":["));
    }

    private static ManifestDraftModel scripted(String name, List<String> promptsSeen, String... responses) {
        Deque<String> queue = new ArrayDeque<>(List.of(responses));
        return new ManifestDraftModel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String draft(ModelRequest request) {
                promptsSeen.add(request.userPrompt());
                if (queue.isEmpty()) {
                    throw new IllegalStateException("Stub model asked for more drafts than were scripted");
                }
                return queue.poll();
            }
        };
    }
}
