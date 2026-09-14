package com.sails.ai.selfserviceapi.onboarding.generate.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to a local Ollama daemon's chat API. Needs a code-capable model with real context — Qwen2.5
 * Coder 32B or similar; a small general-purpose model both hallucinates manifest shapes more freely
 * and has less context to lose to truncation.
 *
 * <p>{@code num_ctx} is always sent explicitly (never left to Ollama's own default, which is small
 * in many builds and truncates long input silently) — see {@link DraftModelProperties.Ollama}. This
 * is the single most likely way this feature would produce a confidently wrong manifest, so it is
 * treated as a correctness requirement, not a tuning knob.
 */
@Component
public class OllamaDraftModel implements ManifestDraftModel {

    private static final Logger log = LoggerFactory.getLogger(OllamaDraftModel.class);
    private static final String NAME = "ollama";

    private final RestClient restClient;
    private final DraftModelProperties properties;

    public OllamaDraftModel(@Qualifier(DraftModelRestClientConfig.OLLAMA) RestClient restClient,
                             DraftModelProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** A short-timeout tags read — enough to know the daemon is up, not enough to block a page load. */
    @Override
    public boolean isAvailable() {
        try {
            restClient.get().uri("/api/tags").retrieve().toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            log.debug("Ollama not reachable at {}: {}", properties.ollama().baseUrl(), e.getMessage());
            return false;
        }
    }

    @Override
    public String draft(ModelRequest request) {
        ChatResponse response = restClient.post()
                .uri("/api/chat")
                .body(new ChatRequest(
                        properties.ollama().model(),
                        List.of(
                                new Message("system", request.systemPrompt()),
                                new Message("user", request.userPrompt())),
                        false,
                        request.jsonSchema(),
                        new Options(properties.ollama().numCtx(), 0)))
                .retrieve()
                .body(ChatResponse.class);
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new ManifestDraftException("Ollama returned an empty response");
        }
        return response.message().content();
    }

    /**
     * {@code format} is written as raw JSON (Ollama's schema-constrained output takes a JSON Schema
     * object, not a string) — {@link ModelRequest#jsonSchema()} is already JSON text, so
     * {@code @JsonRawValue} inlines it unescaped rather than nesting it as a quoted string.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record ChatRequest(String model, List<Message> messages, boolean stream,
                                @JsonRawValue String format, Options options) {
    }

    private record Message(String role, String content) {
    }

    /** {@code temperature: 0} — a repair loop asking the same question twice wants the same answer. */
    private record Options(@JsonProperty("num_ctx") int numCtx, double temperature) {
    }

    private record ChatResponse(Message message) {
    }
}
