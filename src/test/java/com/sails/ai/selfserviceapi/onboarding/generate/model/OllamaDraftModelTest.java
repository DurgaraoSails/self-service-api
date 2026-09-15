package com.sails.ai.selfserviceapi.onboarding.generate.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OllamaDraftModelTest {

    private static final String BASE = "http://localhost:11434";

    private MockRestServiceServer server;
    private OllamaDraftModel model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        DraftModelProperties properties = new DraftModelProperties(true, "ollama", Duration.ofSeconds(60),
                new DraftModelProperties.Ollama(BASE, "qwen2.5-coder:32b", 32_000, 0), null);
        model = new OllamaDraftModel(builder.build(), properties);
    }

    @Test
    void isAvailableWhenTheDaemonAnswers() {
        server.expect(requestTo(BASE + "/api/tags")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"models\":[]}", MediaType.APPLICATION_JSON));

        assertThat(model.isAvailable()).isTrue();

        server.verify();
    }

    @Test
    void isNotAvailableWhenNothingIsListening() {
        server.expect(requestTo(BASE + "/api/tags"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(model.isAvailable()).isFalse();
    }

    /** num_ctx must always be sent explicitly — Ollama's own default silently truncates long input. */
    @Test
    void alwaysSendsAnExplicitContextWindowAndZeroTemperature() {
        server.expect(requestTo(BASE + "/api/chat")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"num_ctx\":32000"),
                        org.hamcrest.Matchers.containsString("\"temperature\":0.0"),
                        org.hamcrest.Matchers.containsString("\"stream\":false"))))
                .andRespond(withSuccess("""
                        {"message":{"role":"assistant","content":"{\\"containers\\":[]}"}}
                        """, MediaType.APPLICATION_JSON));

        String result = model.draft(new ModelRequest("system", "user", "{\"type\":\"object\"}", Duration.ofSeconds(30)));

        assertThat(result).isEqualTo("{\"containers\":[]}");
        server.verify();
    }

    @Test
    void sendsTheJsonSchemaAsRawJsonNotAsAQuotedString() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"format\":{\"type\":\"object\",\"properties\":{}}")))
                .andRespond(withSuccess("""
                        {"message":{"role":"assistant","content":"{}"}}
                        """, MediaType.APPLICATION_JSON));

        model.draft(new ModelRequest("system", "user", "{\"type\":\"object\",\"properties\":{}}", Duration.ofSeconds(30)));

        server.verify();
    }

    @Test
    void anEmptyResponseIsAFailureNotANullDraft() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30))))
                .isInstanceOf(ManifestDraftException.class);
    }

    /**
     * The normal shape of "Ollama isn't running" — a local checkout with nothing configured, the
     * scenario poc-generator must never fail the page for. Must surface as ManifestDraftException,
     * the one type PocManifestGenerationService knows how to degrade gracefully from, not as a raw
     * RestClientException.
     */
    @Test
    void aConnectionFailureBecomesAManifestDraftExceptionNotARawTransportError() {
        server.expect(requestTo(BASE + "/api/chat"))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });

        assertThatThrownBy(() -> model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30))))
                .isInstanceOf(ManifestDraftException.class)
                .hasMessageContaining("Ollama call failed");
    }
}
