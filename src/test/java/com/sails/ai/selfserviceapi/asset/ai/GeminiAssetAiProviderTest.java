package com.sails.ai.selfserviceapi.asset.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sails.ai.selfserviceapi.asset.config.AssetAiTransport;
import com.sails.ai.selfserviceapi.asset.config.AssetHubProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

class GeminiAssetAiProviderTest {

    private static final String GENERATE_CONTENT_URL =
            "https://test-region-aiplatform.googleapis.com/v1/projects/test-project/locations/test-region"
                    + "/publishers/google/models/gemini-test:generateContent";

    private final ObjectMapper objectMapper = new ObjectMapper();
    /** VERTEX_AI so the provider builds the aiplatform path this test's expectations assert. */
    private final AssetHubProperties properties = new AssetHubProperties(true, false,
            new AssetHubProperties.Ai(AssetAiTransport.VERTEX_AI, "test-project", "test-region", null,
                    "gemini-test", 5),
            null);

    private RestClient.Builder restClientBuilder() {
        return RestClient.builder().baseUrl("https://test-region-aiplatform.googleapis.com");
    }

    private static AssetAiSuggestionInput input() {
        return new AssetAiSuggestionInput("DOCUMENT", "Title", "Summary", "Problem", "Impact",
                "Solution", List.of("tag-one"));
    }

    @Test
    void parsesAWellFormedSchemaMatchingResponse() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GENERATE_CONTENT_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":
                        "{\\"suggestedTitle\\":\\"New Title\\",\\"suggestedSummary\\":\\"New summary\\",\\"suggestedTags\\":[\\"a\\",\\"b\\"]}"
                        }]}}]}
                        """, MediaType.APPLICATION_JSON));
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(builder.build(), properties, objectMapper);

        AssetAiSuggestionResult result = aiProvider.suggest(input());

        assertThat(result.suggestedTitle()).isEqualTo("New Title");
        assertThat(result.suggestedSummary()).isEqualTo("New summary");
        assertThat(result.suggestedTags()).containsExactly("a", "b");
        server.verify();
    }

    @Test
    void neverSendsTheSourceUrlAndSeparatesInstructionFromData() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GENERATE_CONTENT_URL))
                .andExpect(content().string(not(containsString("sourceUrl"))))
                .andExpect(jsonPath("$.systemInstruction.parts[0].text", containsString("You catalog internal AI work")))
                .andExpect(jsonPath("$.contents[0].parts[0].text", containsString("Catalog fields")))
                .andExpect(jsonPath("$.contents[0].parts[0].text", not(containsString("You catalog internal AI work"))))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":
                        "{\\"suggestedTitle\\":\\"T\\",\\"suggestedSummary\\":\\"S\\",\\"suggestedTags\\":[]}"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(builder.build(), properties, objectMapper);

        aiProvider.suggest(input());

        server.verify();
    }

    @Test
    void malformedResponseJsonIsAnInvalidProviderResponse() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GENERATE_CONTENT_URL))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"not valid json"}]}}]}
                        """, MediaType.APPLICATION_JSON));
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(builder.build(), properties, objectMapper);

        assertThatThrownBy(() -> aiProvider.suggest(input()))
                .isInstanceOf(AssetAiProviderException.class)
                .satisfies(e -> assertThat(((AssetAiProviderException) e).errorCode()).isEqualTo("INVALID_PROVIDER_RESPONSE"));
    }

    @Test
    void emptyCandidatesListIsAnInvalidProviderResponse() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GENERATE_CONTENT_URL))
                .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(builder.build(), properties, objectMapper);

        assertThatThrownBy(() -> aiProvider.suggest(input()))
                .isInstanceOf(AssetAiProviderException.class)
                .satisfies(e -> assertThat(((AssetAiProviderException) e).errorCode()).isEqualTo("INVALID_PROVIDER_RESPONSE"));
    }

    @Test
    void aNon2xxResponseIsAProviderError() {
        RestClient.Builder builder = restClientBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GENERATE_CONTENT_URL)).andRespond(withServerError());
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(builder.build(), properties, objectMapper);

        assertThatThrownBy(() -> aiProvider.suggest(input()))
                .isInstanceOf(AssetAiProviderException.class)
                .satisfies(e -> assertThat(((AssetAiProviderException) e).errorCode()).isEqualTo("PROVIDER_ERROR"));
    }

    @Test
    void aConnectionFailureIsAProviderTimeout() {
        // Port 1 on loopback: nothing listens there, so the OS refuses the connection immediately
        // (a fast, deterministic IOException) rather than depending on any real network/DNS
        // behavior, which this sandboxed environment cannot be relied on to reproduce consistently.
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        RestClient unreachableClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:1")
                .requestFactory(requestFactory)
                .build();
        GeminiAssetAiProvider aiProvider = new GeminiAssetAiProvider(unreachableClient, properties, objectMapper);

        assertThatThrownBy(() -> aiProvider.suggest(input()))
                .isInstanceOf(AssetAiProviderException.class)
                .satisfies(e -> assertThat(((AssetAiProviderException) e).errorCode()).isEqualTo("PROVIDER_TIMEOUT"));
    }
}
