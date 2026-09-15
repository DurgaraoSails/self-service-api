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

    /**
     * A poc.yaml draft is small — a few KB of JSON at most. Capping generation this low is a second,
     * independent guard (alongside schema constraining) against a model that runs away rather than
     * emitting the closing brace, which would otherwise tie up the connection until the read timeout.
     */
    private static final int MAX_OUTPUT_TOKENS = 8192;

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

    /**
     * The transport call is wrapped separately from the empty-response check below: a connection
     * failure (Ollama not running — the normal case in a local checkout with nothing configured)
     * must reach {@code PocManifestGenerationService} as a {@link ManifestDraftException}, the one
     * type it knows how to degrade gracefully from, rather than as a raw
     * {@link RestClientException} that would surface as a 500.
     */
    @Override
    public String draft(ModelRequest request) {
        ChatResponse response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .body(new ChatRequest(
                            properties.ollama().model(),
                            List.of(
                                    new Message("system", request.systemPrompt()),
                                    new Message("user", request.userPrompt())),
                            false,
                            request.jsonSchema(),
                            new Options(properties.ollama().numCtx(), MAX_OUTPUT_TOKENS,
                                    properties.ollama().temperature())))
                    .retrieve()
                    .body(ChatResponse.class);
        } catch (RestClientException e) {
            throw new ManifestDraftException("Ollama call failed: " + e.getMessage(), e);
        }
        if (response == null || response.message() == null || response.message().content() == null) {
            throw new ManifestDraftException("Ollama returned an empty response");
        }
        // "length" means num_predict was hit before the model reached its closing brace — the JSON
        // is truncated and therefore unparseable, so this fails the attempt (feeding the repair
        // loop) rather than handing ManifestDraftService malformed output to puzzle over.
        if ("length".equals(response.doneReason())) {
            throw new ManifestDraftException("Ollama's response was truncated at " + MAX_OUTPUT_TOKENS
                    + " tokens before finishing");
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

    private record Options(@JsonProperty("num_ctx") int numCtx, @JsonProperty("num_predict") int numPredict,
                            double temperature) {
    }

    private record ChatResponse(Message message, @JsonProperty("done_reason") String doneReason) {
    }
}
