package com.sails.ai.selfserviceapi.asset.ai;

import com.sails.ai.selfserviceapi.asset.config.AssetHubProperties;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

/**
 * Calls Gemini on Vertex AI's {@code generateContent} REST endpoint with a JSON response schema
 * (Vertex's structured-output feature — the equivalent of Anthropic's {@code output_config.format}
 * for a provider that has no dedicated SDK in this project). Single request, no tools, no
 * multi-turn: see docs/specs/asset-hub.md's AI Suggestion Contract. Authenticates with the same
 * Application Default Credentials pattern deploypipeline.config.PipelineRestClientConfig already
 * uses for Cloud Build/Cloud Run, so no separate API key is needed in a deployed environment.
 */
@Component
@ConditionalOnProperty(prefix = "asset-hub", name = "ai-enabled", havingValue = "true")
public class GeminiAssetAiProvider implements AssetAiProvider {

    private static final String PROVIDER_NAME = "gemini";

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "suggestedTitle", Map.of("type", "STRING"),
                    "suggestedSummary", Map.of("type", "STRING"),
                    "suggestedTags", Map.of(
                            "type", "ARRAY",
                            "items", Map.of("type", "STRING"),
                            "maxItems", 10)),
            "required", List.of("suggestedTitle", "suggestedSummary", "suggestedTags"));

    private final RestClient vertexAiRestClient;
    private final AssetHubProperties properties;
    private final ObjectMapper objectMapper;

    public GeminiAssetAiProvider(@Qualifier("vertexAiRestClient") RestClient vertexAiRestClient,
                                  AssetHubProperties properties, ObjectMapper objectMapper) {
        this.vertexAiRestClient = vertexAiRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String modelName() {
        return properties.ai().model();
    }

    @Override
    public AssetAiSuggestionResult suggest(AssetAiSuggestionInput input) {
        GenerateContentResponse response = call(input);
        String rawJson = extractText(response);
        SuggestionPayload payload = parsePayload(rawJson);
        return new AssetAiSuggestionResult(payload.suggestedTitle(), payload.suggestedSummary(),
                payload.suggestedTags() == null ? List.of() : payload.suggestedTags());
    }

    private GenerateContentResponse call(AssetAiSuggestionInput input) {
        GenerateContentRequest request = buildRequest(input);
        try {
            return vertexAiRestClient.post()
                    .uri("/v1/projects/{project}/locations/{region}/publishers/google/models/{model}:generateContent",
                            properties.ai().projectId(), properties.ai().region(), properties.ai().model())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GenerateContentResponse.class);
        } catch (ResourceAccessException e) {
            throw new AssetAiProviderException("PROVIDER_TIMEOUT", "Gemini request timed out or was unreachable", e);
        } catch (RestClientResponseException e) {
            throw new AssetAiProviderException("PROVIDER_ERROR",
                    "Gemini request failed with status " + e.getStatusCode().value(), e);
        }
    }

    private GenerateContentRequest buildRequest(AssetAiSuggestionInput input) {
        String canonicalInput;
        try {
            canonicalInput = objectMapper.writeValueAsString(input);
        } catch (RuntimeException e) {
            throw new AssetAiProviderException("PROVIDER_ERROR", "Failed to serialize suggestion input", e);
        }
        String prompt = """
                You catalog internal AI work for an employee directory. Using ONLY the JSON fields \
                below — entered directly by the employee — propose an improved title, a \
                concise one-paragraph summary, and up to 10 relevant tags. Do not invent facts not \
                implied by the fields, and do not reference or infer anything about a source link. \
                Respond with JSON matching the given schema only.

                Fields:
                %s""".formatted(canonicalInput);

        GenerationConfig config = new GenerationConfig("application/json", RESPONSE_SCHEMA, 0.2);
        return new GenerateContentRequest(
                List.of(new RequestContent("user", List.of(new RequestPart(prompt)))), config);
    }

    private String extractText(GenerateContentResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            throw new AssetAiProviderException("INVALID_PROVIDER_RESPONSE", "Gemini returned no candidates");
        }
        Candidate candidate = response.candidates().get(0);
        if (candidate.content() == null || candidate.content().parts() == null
                || candidate.content().parts().isEmpty()) {
            throw new AssetAiProviderException("INVALID_PROVIDER_RESPONSE", "Gemini candidate had no content parts");
        }
        String text = candidate.content().parts().get(0).text();
        if (text == null || text.isBlank()) {
            throw new AssetAiProviderException("INVALID_PROVIDER_RESPONSE", "Gemini candidate text was empty");
        }
        return text;
    }

    private SuggestionPayload parsePayload(String rawJson) {
        try {
            return objectMapper.readValue(rawJson, SuggestionPayload.class);
        } catch (RuntimeException e) {
            throw new AssetAiProviderException("INVALID_PROVIDER_RESPONSE",
                    "Gemini response did not match the suggestion schema", e);
        }
    }

    // -- Vertex AI generateContent wire shapes (request) --------------------------------------

    private record GenerateContentRequest(List<RequestContent> contents, GenerationConfig generationConfig) {
    }

    private record RequestContent(String role, List<RequestPart> parts) {
    }

    private record RequestPart(String text) {
    }

    private record GenerationConfig(String responseMimeType, Map<String, Object> responseSchema, double temperature) {
    }

    // -- Vertex AI generateContent wire shapes (response) --------------------------------------

    private record GenerateContentResponse(List<Candidate> candidates) {
    }

    private record Candidate(Content content) {
    }

    private record Content(List<Part> parts) {
    }

    private record Part(String text) {
    }

    // -- structured suggestion payload, matches RESPONSE_SCHEMA --------------------------------

    private record SuggestionPayload(String suggestedTitle, String suggestedSummary, List<String> suggestedTags) {
    }
}
