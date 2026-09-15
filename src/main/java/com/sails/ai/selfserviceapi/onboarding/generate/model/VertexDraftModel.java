package com.sails.ai.selfserviceapi.onboarding.generate.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Talks to Vertex AI's {@code generateContent} endpoint. Chosen over calling a provider directly
 * because this platform already authenticates to GCP with Application Default Credentials for
 * Cloud Build and Cloud Run — this adapter adds no new secret to manage — and Vertex serves both
 * Gemini and Claude models behind the one shape this class calls, so the model choice stays a
 * config change ({@link DraftModelProperties.Vertex#model()}), never a code change.
 */
@Component
public class VertexDraftModel implements ManifestDraftModel {

    private static final Logger log = LoggerFactory.getLogger(VertexDraftModel.class);
    private static final String NAME = "vertex";

    /** Same rationale as {@code OllamaDraftModel.MAX_OUTPUT_TOKENS} — a poc.yaml draft is a few KB of JSON at most. */
    private static final int MAX_OUTPUT_TOKENS = 8192;

    private final RestClient restClient;
    private final DraftModelProperties properties;

    public VertexDraftModel(@Qualifier(DraftModelRestClientConfig.VERTEX) RestClient restClient,
                             DraftModelProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    /**
     * Whether the adapter is configured at all — {@code poc-generator.vertex.project} is required
     * for every call this class makes, and there is no cheap unauthenticated reachability probe for
     * Vertex the way Ollama's {@code /api/tags} is. Credential/permission problems still surface at
     * the first real {@link #draft} call, not here.
     */
    @Override
    public boolean isAvailable() {
        boolean configured = properties.vertex().project() != null && !properties.vertex().project().isBlank();
        if (!configured) {
            log.debug("poc-generator.vertex.project is not set — Vertex AI is not available");
        }
        return configured;
    }

    /**
     * The transport call is wrapped separately from the empty-response check below, and deliberately
     * catches {@link RuntimeException} rather than only {@link org.springframework.web.client.RestClientException}:
     * a missing/invalid credential fails inside the request interceptor as an unchecked
     * {@code UncheckedIOException} from {@code GoogleCredentials.getApplicationDefault()}, which
     * {@code RestClient} does not itself wrap the way it wraps a transport {@code IOException}. Since
     * {@link #isAvailable()} can only check that {@code poc-generator.vertex.project} is set — there
     * is no cheap way to verify credentials without spending a real call — this catch is what actually
     * makes "configured but not authenticated" (the common local-checkout case) degrade to
     * {@code PocManifestGenerationService}'s graceful UNAVAILABLE outcome instead of a 500.
     */
    @Override
    public String draft(ModelRequest request) {
        GenerateContentResponse response;
        try {
            response = restClient.post()
                    .uri(generateContentPath())
                    .body(new GenerateContentRequest(
                            List.of(new Content("user", List.of(new Part(request.userPrompt())))),
                            new SystemInstruction(List.of(new Part(request.systemPrompt()))),
                            new GenerationConfig("application/json", request.jsonSchema(), 0, MAX_OUTPUT_TOKENS)))
                    .retrieve()
                    .body(GenerateContentResponse.class);
        } catch (RuntimeException e) {
            throw new ManifestDraftException("Vertex AI call failed: " + e.getMessage(), e);
        }

        Candidate candidate = firstCandidate(response);
        if (candidate == null) {
            throw new ManifestDraftException("Vertex AI returned no candidates");
        }
        // MAX_TOKENS (and anything else other than STOP) means the response was cut off before the
        // model finished — the JSON is truncated and therefore unparseable, so this fails the
        // attempt (feeding the repair loop) rather than handing malformed output onward. A blank
        // finishReason is treated as STOP: older API responses may omit it on a normal completion.
        String finishReason = candidate.finishReason();
        if (finishReason != null && !finishReason.isBlank() && !"STOP".equals(finishReason)) {
            throw new ManifestDraftException("Vertex AI response did not finish normally (finishReason="
                    + finishReason + ")");
        }

        String text = firstTextPart(candidate);
        if (text == null) {
            throw new ManifestDraftException("Vertex AI returned no candidates");
        }
        return text;
    }

    private String generateContentPath() {
        DraftModelProperties.Vertex vertex = properties.vertex();
        return "/v1/projects/%s/locations/%s/publishers/google/models/%s:generateContent"
                .formatted(vertex.project(), vertex.location(), vertex.model());
    }

    private Candidate firstCandidate(GenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        return response.candidates().get(0);
    }

    private String firstTextPart(Candidate candidate) {
        Content content = candidate.content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().get(0).text();
    }

    private record GenerateContentRequest(List<Content> contents, SystemInstruction systemInstruction,
                                           GenerationConfig generationConfig) {
    }

    private record Content(String role, List<Part> parts) {
    }

    private record SystemInstruction(List<Part> parts) {
    }

    private record Part(String text) {
    }

    /**
     * {@code responseSchema} is raw JSON, the same reason {@code OllamaDraftModel}'s {@code format}
     * is — {@link ModelRequest#jsonSchema()} is already JSON text.
     */
    private record GenerationConfig(@JsonProperty("responseMimeType") String responseMimeType,
                                     @JsonProperty("responseSchema") @JsonRawValue String responseSchema,
                                     double temperature, @JsonProperty("maxOutputTokens") int maxOutputTokens) {
    }

    private record GenerateContentResponse(List<Candidate> candidates) {
    }

    private record Candidate(Content content, @JsonProperty("finishReason") String finishReason) {
    }
}
