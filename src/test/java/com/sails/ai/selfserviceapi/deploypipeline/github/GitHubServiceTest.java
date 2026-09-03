package com.sails.ai.selfserviceapi.deploypipeline.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubServiceTest {

    private static final String BASE = "https://api.github.com";
    private static final GitHubRepoRef REPO = new GitHubRepoRef("DurgaraoSails", "dummy-poc");
    private static final String TAG_URL = BASE + "/repos/DurgaraoSails/dummy-poc/git/refs";
    private static final String READ_TAG_URL = BASE + "/repos/DurgaraoSails/dummy-poc/git/ref/tags/1.0.1";

    private static final String ALREADY_EXISTS = """
            {"message":"Reference already exists",
             "documentation_url":"https://docs.github.com/rest/git/refs#create-a-reference",
             "status":"422"}
            """;

    private MockRestServiceServer server;
    private GitHubService gitHubService;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        PipelineProperties properties = new PipelineProperties(
                "local", "", "", "ghp_test", false, false, "", Duration.ofMinutes(20),
                Duration.ofMinutes(20), Duration.ofSeconds(10));
        gitHubService = new GitHubService(builder.build(), properties);
    }

    private static String tagPointingAt(String sha) {
        return """
                {"ref":"refs/tags/1.0.1","object":{"sha":"%s","type":"commit"}}
                """.formatted(sha);
    }

    @Test
    void aRetryOfAnAlreadyTaggedReleaseContinuesInsteadOfFailing() {
        server.expect(requestTo(TAG_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(ALREADY_EXISTS).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(READ_TAG_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(tagPointingAt("abc123"), MediaType.APPLICATION_JSON));

        assertThatCode(() -> gitHubService.createTagIfAbsent(REPO, "1.0.1", "abc123"))
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void aTagAlreadyPointingAtDifferentCodeStopsTheRelease() {
        server.expect(requestTo(TAG_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body(ALREADY_EXISTS).contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(READ_TAG_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(tagPointingAt("999999"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gitHubService.createTagIfAbsent(REPO, "1.0.1", "abc123"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Refusing to reuse a version label");
    }

    @Test
    void someOther422SurfacesItsRealCauseRatherThanALookupFailure() {
        server.expect(requestTo(TAG_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"message\":\"Object does not exist\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gitHubService.createTagIfAbsent(REPO, "1.0.1", "deadbeef"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Object does not exist");

        server.verify();
    }

    @Test
    void aFreshTagIsCreatedWithoutAnyLookup() {
        server.expect(requestTo(TAG_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(tagPointingAt("abc123"), MediaType.APPLICATION_JSON));

        assertThatCode(() -> gitHubService.createTagIfAbsent(REPO, "1.0.1", "abc123"))
                .doesNotThrowAnyException();

        server.verify();
    }

    @Test
    void refusesToDoAnythingWithNoTokenConfigured() {
        // SimpleClientHttpRequestFactory, not RestClient's JDK-HttpClient default: the default
        // opens a loopback socket for its async selector at build time (not first use), which
        // fails in some sandboxed/restricted-network environments. No request should ever
        // actually fire here — requireToken() throws first — but building the client must not.
        RestClient client = RestClient.builder().baseUrl(BASE)
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
                .build();
        PipelineProperties noToken = new PipelineProperties(
                "local", "", "", "", false, false, "", Duration.ofMinutes(20),
                Duration.ofMinutes(20), Duration.ofSeconds(10));

        assertThatThrownBy(() -> new GitHubService(client, noToken).getDefaultBranch(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("No GitHub token configured");
    }

    @Test
    void tryGetFileContentDecodesGitHubsWrappedBase64() {
        String yaml = "apiVersion: sails.poc/v1\ncontainers:\n  - name: web\n    role: ingress\n";
        // GitHub's contents API wraps base64 at 60 chars with embedded newlines — this is
        // exactly what the MIME encoder produces, and what the MIME *decoder* is specifically
        // needed to read back.
        String wrapped = Base64.getMimeEncoder(60, "\n".getBytes()).encodeToString(yaml.getBytes());
        String url = BASE + "/repos/DurgaraoSails/dummy-poc/contents/poc.yaml?ref=abc123def456";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"content\":\"" + wrapped.replace("\n", "\\n") + "\",\"encoding\":\"base64\"}",
                        MediaType.APPLICATION_JSON));

        Optional<String> content = gitHubService.tryGetFileContent(REPO, "poc.yaml", "abc123def456");

        assertThat(content).contains(yaml);
        server.verify();
    }

    @Test
    void tryGetFileContentReturnsEmptyOn404() {
        String url = BASE + "/repos/DurgaraoSails/dummy-poc/contents/poc.yaml?ref=abc123def456";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}").contentType(MediaType.APPLICATION_JSON));

        Optional<String> content = gitHubService.tryGetFileContent(REPO, "poc.yaml", "abc123def456");

        assertThat(content).isEmpty();
        server.verify();
    }

    @Test
    void tryGetFileContentSurfacesAnyErrorOtherThanNotFound() {
        String url = BASE + "/repos/DurgaraoSails/dummy-poc/contents/poc.yaml?ref=abc123def456";
        server.expect(requestTo(url)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"API rate limit exceeded\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gitHubService.tryGetFileContent(REPO, "poc.yaml", "abc123def456"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("rate limit");
    }

    @Test
    void parseRepoUrlHandlesTheDotGitSuffix() {
        GitHubRepoRef repo = gitHubService.parseRepoUrl("https://github.com/DurgaraoSails/dummy-poc.git");

        org.assertj.core.api.Assertions.assertThat(repo.owner()).isEqualTo("DurgaraoSails");
        org.assertj.core.api.Assertions.assertThat(repo.name()).isEqualTo("dummy-poc");
    }
}
