package com.sails.ai.selfserviceapi.onboarding.generate.model;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which LLM provider drafts a poc.yaml, and how to reach it. Nothing in
 * {@code onboarding.generate}'s pipeline reads a provider name directly — it depends on
 * {@link ManifestDraftModel} and lets Spring wire whichever adapter's {@link ManifestDraftModel#name()}
 * matches {@link #provider()}.
 *
 * @param enabled whether the generate endpoint is offered at all. False lets an environment with
 *                no model reachable — and no wish to expose the button — turn the feature off
 *                outright rather than let every request fall through to the degraded path.
 * @param provider "ollama" or "vertex".
 * @param timeout  the per-call timeout passed to {@link ModelRequest#timeout()}. A model call is
 *                 expected to run far longer than a plain REST call, so this is independent of the
 *                 adapters' own connect/read timeouts.
 */
@ConfigurationProperties(prefix = "poc-generator")
public record DraftModelProperties(
        boolean enabled,
        String provider,
        Duration timeout,
        Ollama ollama,
        Vertex vertex
) {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    public DraftModelProperties {
        if (provider == null || provider.isBlank()) {
            provider = "ollama";
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (ollama == null) {
            ollama = Ollama.defaults();
        }
        if (vertex == null) {
            vertex = Vertex.defaults();
        }
    }

    /**
     * @param baseUrl     where the local Ollama daemon listens.
     * @param model       a code-capable model with real context — see the class javadoc on
     *                    {@code OllamaDraftModel} for why a small general-purpose model is the
     *                    wrong choice here.
     * @param numCtx      the context window to request, in tokens. Always set explicitly: Ollama's
     *                    own default is small (2048 in many builds) and silently truncates
     *                    anything longer, which here means a manifest confidently drafted from half
     *                    a repository. {@code RepoInventoryService}'s byte cap is a second,
     *                    independent guard against the same failure.
     * @param temperature passed through as-is; the pipeline itself always asks for {@code 0} at the
     *                    call site, this field exists only so it is visible in configuration.
     */
    public record Ollama(String baseUrl, String model, int numCtx, double temperature) {

        static Ollama defaults() {
            return new Ollama("http://localhost:11434", "qwen2.5-coder:32b", 32_000, 0);
        }
    }

    /**
     * @param project  the GCP project Vertex AI calls are billed and quota'd against.
     * @param location a Vertex AI region, e.g. {@code us-central1}.
     * @param model    a Vertex-hosted model id — Gemini or a Claude model on Vertex, both reachable
     *                 through the same {@code generateContent} shape this adapter calls.
     */
    public record Vertex(String project, String location, String model) {

        static Vertex defaults() {
            return new Vertex(null, "us-central1", "gemini-2.0-flash-001");
        }
    }
}
