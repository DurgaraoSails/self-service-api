package com.sails.ai.selfserviceapi.onboarding.generate.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class VertexDraftModelTest {

    private static final String BASE = "https://aiplatform.googleapis.com";
    private static final String URL = BASE
            + "/v1/projects/acme-poc/locations/us-central1/publishers/google/models/gemini-2.0-flash-001:generateContent";

    private MockRestServiceServer server;
    private VertexDraftModel model;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        DraftModelProperties properties = new DraftModelProperties(true, "vertex", Duration.ofSeconds(60),
                null, new DraftModelProperties.Vertex("acme-poc", "us-central1", "gemini-2.0-flash-001"), null);
        model = new VertexDraftModel(builder.build(), properties);
    }

    @Test
    void isAvailableOnlyWhenAProjectIsConfigured() {
        assertThat(model.isAvailable()).isTrue();

        DraftModelProperties unconfigured = new DraftModelProperties(true, "vertex", Duration.ofSeconds(60),
                null, new DraftModelProperties.Vertex(null, "us-central1", "gemini-2.0-flash-001"), null);
        RestClient noRequestExpected = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                .build();
        assertThat(new VertexDraftModel(noRequestExpected, unconfigured).isAvailable()).isFalse();
    }

    @Test
    void sendsTheSchemaAsRawJsonAndAsksForJsonMime() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"responseMimeType\":\"application/json\""),
                        org.hamcrest.Matchers.containsString("\"responseSchema\":{\"type\":\"object\"}"),
                        org.hamcrest.Matchers.containsString("\"temperature\":0.0"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"{\\"containers\\":[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));

        String result = model.draft(new ModelRequest("system", "user", "{\"type\":\"object\"}", Duration.ofSeconds(30)));

        assertThat(result).isEqualTo("{\"containers\":[]}");
        server.verify();
    }

    /** usageMetadata isn't used by the draft itself — this only proves the response DTO actually parses it, for logging. */
    @Test
    void parsesUsageMetadataFromTheResponseWhenPresent() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"role":"model","parts":[{"text":"{}"}]}}],
                         "usageMetadata":{"promptTokenCount":123,"candidatesTokenCount":45,"totalTokenCount":168}}
                        """, MediaType.APPLICATION_JSON));

        String result = model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30)));

        assertThat(result).isEqualTo("{}");
    }

    @Test
    void noCandidatesIsAFailureNotANullDraft() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30))))
                .isInstanceOf(ManifestDraftException.class);
    }

    /**
     * isAvailable() only checks that a project id is configured — it cannot cheaply verify
     * credentials without spending a real call. A missing/invalid credential (the common local
     * checkout case: a project id is set somewhere but `gcloud auth application-default login`
     * never ran) fails inside the request interceptor, so draft() must still turn that into a
     * ManifestDraftException on its own rather than letting an unchecked failure escape as a 500.
     * Simulated here with a transport failure, the closest thing MockRestServiceServer can produce
     * to the interceptor-level failure a real credential problem causes.
     */
    /** A finishReason other than STOP (e.g. MAX_TOKENS) means the response was cut off — the JSON is truncated. */
    @Test
    void aTruncatedResponseBecomesAFailureNotAMalformedDraft() {
        server.expect(requestTo(URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"finishReason":"MAX_TOKENS","content":{"role":"model","parts":[{"text":"{\\"containers\\":["}]}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30))))
                .isInstanceOf(ManifestDraftException.class)
                .hasMessageContaining("MAX_TOKENS");
    }

    @Test
    void aTransportFailureBecomesAManifestDraftExceptionNotARawTransportError() {
        server.expect(requestTo(URL))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });

        assertThatThrownBy(() -> model.draft(new ModelRequest("s", "u", "{}", Duration.ofSeconds(30))))
                .isInstanceOf(ManifestDraftException.class)
                .hasMessageContaining("Vertex AI call failed");
    }
}
