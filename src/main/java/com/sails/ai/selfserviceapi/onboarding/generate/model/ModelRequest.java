package com.sails.ai.selfserviceapi.onboarding.generate.model;

import java.time.Duration;

/**
 * One call to a {@link ManifestDraftModel}.
 *
 * @param systemPrompt the model's standing instructions — what a manifest is, the JSON shape it
 *                      must return, and the rule that no secret value found in the repo may ever
 *                      appear in the response.
 * @param userPrompt    the repository-specific half: the inventory evidence, and, on a repair
 *                      attempt, the previous draft plus {@code ManifestValidator}'s violations.
 * @param jsonSchema    the JSON Schema the response must conform to. Passed straight through to
 *                      the provider's own schema-constrained output feature (Ollama's
 *                      {@code format}, Vertex's {@code responseSchema}) rather than merely
 *                      described in the prompt — constraining generation is far more reliable than
 *                      asking for it.
 * @param timeout       per-call timeout, independent of the adapter's connection-level timeout —
 *                      a model call is expected to take far longer than a GitHub API call.
 */
public record ModelRequest(String systemPrompt, String userPrompt, String jsonSchema, Duration timeout) {
}
