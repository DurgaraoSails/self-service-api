package com.sails.ai.selfserviceapi.deploypipeline.github;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import java.time.Duration;
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
                "cloud-build", "", "ghp_test", false, false, Duration.ofMinutes(20), Duration.ofSeconds(10), null);
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
                "cloud-build", "", "", false, false, Duration.ofMinutes(20), Duration.ofSeconds(10), null);

        assertThatThrownBy(() -> new GitHubService(client, noToken).getDefaultBranch(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("No GitHub token configured");
    }

    @Test
    void parseRepoUrlHandlesTheDotGitSuffix() {
        GitHubRepoRef repo = gitHubService.parseRepoUrl("https://github.com/DurgaraoSails/dummy-poc.git");

        org.assertj.core.api.Assertions.assertThat(repo.owner()).isEqualTo("DurgaraoSails");
        org.assertj.core.api.Assertions.assertThat(repo.name()).isEqualTo("dummy-poc");
    }

    // --- which branch a version is cut from -------------------------------------------------

    private static final String REPO_URL = BASE + "/repos/DurgaraoSails/dummy-poc";
    private static final String MAIN_REF_URL = BASE + "/repos/DurgaraoSails/dummy-poc/git/ref/heads/main";
    private static final String DEVELOP_REF_URL = BASE + "/repos/DurgaraoSails/dummy-poc/git/ref/heads/develop";

    private GitHubService serviceDeployingFrom(String deployBranch) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        return new GitHubService(builder.build(), new PipelineProperties(
                "cloud-build", "", "ghp_test", false, false,
                Duration.ofMinutes(20), Duration.ofSeconds(10), deployBranch));
    }

    private static String refPointingAt(String sha) {
        return """
                {"ref":"refs/heads/x","object":{"sha":"%s","type":"commit"}}
                """.formatted(sha);
    }

    /**
     * The default: follow whatever the repository itself calls its default branch. Two calls —
     * read the repo to learn the name, then read that branch's head.
     */
    @Test
    void cutsFromTheRepositoriesOwnDefaultBranchWhenNoBranchIsPinned() {
        GitHubService service = serviceDeployingFrom(null);
        server.expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"default_branch\":\"develop\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DEVELOP_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("dev999"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(service.getDeployBranchHeadSha(REPO)).isEqualTo("dev999");

        server.verify();
    }

    /**
     * Pinned: go straight to that branch. Notably it must NOT read the repo first — asking for the
     * default branch and then ignoring it would make a repo whose default is something else look
     * like it deployed from its default when it did not.
     */
    @Test
    void cutsFromThePinnedBranchWithoutConsultingTheRepositoriesDefault() {
        GitHubService service = serviceDeployingFrom("main");
        server.expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("main111"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(service.getDeployBranchHeadSha(REPO)).isEqualTo("main111");

        server.verify();
    }

    /** The failure this setting introduces: a repo that simply has no branch by that name. */
    @Test
    void saysWhichSettingIsAtFaultWhenThePinnedBranchDoesNotExist() {
        GitHubService service = serviceDeployingFrom("main");
        server.expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> service.getDeployBranchHeadSha(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Branch 'main' does not exist")
                .hasMessageContaining("pipeline.deploy-branch");
    }

    /** Blank is the same as unset — the property is optional, not a required empty string. */
    @Test
    void treatsABlankPinnedBranchAsNotPinned() {
        GitHubService service = serviceDeployingFrom("   ");
        server.expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("abc123"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(service.getDeployBranchHeadSha(REPO)).isEqualTo("abc123");

        server.verify();
    }

    // --- push-access precondition ----------------------------------------------------------

    private void respondToRepoReadWith(String body) {
        server.expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void allowsADeployWhenTheTokenCanPushToTheRepository() {
        respondToRepoReadWith("""
                {"default_branch":"main","permissions":{"admin":false,"push":true,"pull":true}}
                """);

        assertThatCode(() -> gitHubService.requirePushAccess(REPO)).doesNotThrowAnyException();

        server.verify();
    }

    /**
     * The case this precondition exists for: a public repository owned by someone else. It reads
     * fine — which is why the failure used to surface much later, as a 403 from the tag call.
     */
    @Test
    void refusesADeployWhenTheTokenCanOnlyReadTheRepository() {
        respondToRepoReadWith("""
                {"default_branch":"main","permissions":{"admin":false,"push":false,"pull":true}}
                """);

        assertThatThrownBy(() -> gitHubService.requirePushAccess(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("cannot push to")
                .hasMessageContaining("dummy-poc")
                .hasMessageContaining("release tag");
    }

    /** A missing permissions block must not read as "allowed" — absence is not permission. */
    @Test
    void refusesADeployWhenTheResponseCarriesNoPermissionsAtAll() {
        respondToRepoReadWith("""
                {"default_branch":"main"}
                """);

        assertThatThrownBy(() -> gitHubService.requirePushAccess(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("cannot push to");
    }

    /**
     * An archived repository is frozen read-only for everyone, yet still reports push: true — so
     * without this the deploy passes the precondition and fails later at the tag, with exactly the
     * raw 403 the precondition exists to replace.
     */
    @Test
    void refusesADeployToAnArchivedRepositoryEvenThoughItReportsPushAccess() {
        respondToRepoReadWith("""
                {"default_branch":"main","archived":true,"permissions":{"admin":true,"push":true,"pull":true}}
                """);

        assertThatThrownBy(() -> gitHubService.requirePushAccess(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("is archived")
                .hasMessageNotContaining("cannot push to");
    }

    @Test
    void allowsADeployToAnActiveRepositoryThatSaysSoExplicitly() {
        respondToRepoReadWith("""
                {"default_branch":"main","archived":false,"permissions":{"admin":false,"push":true,"pull":true}}
                """);

        assertThatCode(() -> gitHubService.requirePushAccess(REPO)).doesNotThrowAnyException();
    }

    /**
     * GitHub answers 404 rather than 403 for a private repo the token cannot see, so a typo'd URL
     * and an access problem arrive identically. The message has to cover both.
     */
    @Test
    void reportsAMissingOrInvisibleRepositoryDistinctlyFromAPushFailure() {
        server.expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gitHubService.requirePushAccess(REPO))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("was not found, or is not visible")
                .hasMessageNotContaining("cannot push to");
    }
}
