package com.sails.ai.selfserviceapi.deploypipeline.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The only component that talks to GitHub. Version numbers are allocated by
 * {@code PocDeploymentService}, so nothing here computes one — this reads the default branch's
 * head and writes the tag it was told to write.
 */
@Service
public class GitHubService {

    private static final Logger log = LoggerFactory.getLogger(GitHubService.class);

    // github.com/{owner}/{repo}, with or without a trailing .git or slash, plus the SSH form.
    private static final Pattern REPO_URL_PATTERN =
            Pattern.compile("github\\.com[/:]([^/]+)/([^/.]+)(\\.git)?/?$");

    private final RestClient gitHubRestClient;
    private final PipelineProperties properties;

    public GitHubService(RestClient gitHubRestClient, PipelineProperties properties) {
        this.gitHubRestClient = gitHubRestClient;
        this.properties = properties;
    }

    public GitHubRepoRef parseRepoUrl(String githubUrl) {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new GitHubApiException("No repository URL supplied");
        }
        Matcher matcher = REPO_URL_PATTERN.matcher(githubUrl.trim());
        if (!matcher.find()) {
            throw new GitHubApiException("Not a recognizable GitHub repo URL: " + githubUrl);
        }
        return new GitHubRepoRef(matcher.group(1), matcher.group(2));
    }

    /** Never assumes "main" — repositories differ, and a wrong guess fails confusingly. */
    public String getDefaultBranch(GitHubRepoRef repo) {
        return get("/repos/{owner}/{repo}", RepoInfo.class, repo.owner(), repo.name()).defaultBranch();
    }

    public String getBranchHeadSha(GitHubRepoRef repo, String branch) {
        return get("/repos/{owner}/{repo}/git/ref/heads/{branch}", GitRefResponse.class,
                repo.owner(), repo.name(), branch).object().sha();
    }

    public String getDefaultBranchHeadSha(GitHubRepoRef repo) {
        return getBranchHeadSha(repo, getDefaultBranch(repo));
    }

    /**
     * Creates a lightweight tag pointing at {@code commitSha}, tolerating one that already
     * exists at that same commit. A deployment is retried whenever the pipeline dies mid-run, and
     * the previous attempt may well have got as far as tagging — treating "already exists at the
     * expected commit" as success is what makes the tag step safe to repeat. A tag on a
     * *different* commit is refused: silently reusing a version label for different code would
     * make the released version meaningless.
     */
    public void createTagIfAbsent(GitHubRepoRef repo, String tagName, String commitSha) {
        requireToken();
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/git/refs", repo.owner(), repo.name())
                    .body(new CreateRefRequest("refs/tags/" + tagName, commitSha))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY) && isAlreadyExists(e)) {
                verifyExistingTagMatches(repo, tagName, commitSha);
                return;
            }
            throw wrap(e, "create tag " + tagName + " on " + repo);
        }
    }

    /**
     * GitHub answers 422 for several distinct validation failures — a bad commit SHA and a
     * malformed ref name among them. Only "already exists" is safe to continue from; treating all
     * of them that way would swap a clear error for a confusing tag lookup that then 404s.
     */
    private boolean isAlreadyExists(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return body != null && body.contains("Reference already exists");
    }

    private void verifyExistingTagMatches(GitHubRepoRef repo, String tagName, String commitSha) {
        String existingSha = get("/repos/{owner}/{repo}/git/ref/tags/{tag}", GitRefResponse.class,
                repo.owner(), repo.name(), tagName).object().sha();

        if (!existingSha.equals(commitSha)) {
            throw new GitHubApiException(
                    "Tag %s already exists on %s at commit %s, but this deployment is for %s. "
                            .formatted(tagName, repo, existingSha, commitSha)
                            + "Refusing to reuse a version label for different code.");
        }
        log.info("Tag {} already present on {} at the expected commit — carrying on", tagName, repo);
    }

    private <T> T get(String uri, Class<T> type, Object... uriVars) {
        requireToken();
        try {
            return gitHubRestClient.get().uri(uri, uriVars).retrieve().body(type);
        } catch (RestClientResponseException e) {
            throw wrap(e, "GET " + uri);
        }
    }

    /** Fails at first use, not at boot — see PipelineRestClientConfig for why. */
    private void requireToken() {
        if (!properties.hasGithubToken()) {
            throw new GitHubApiException(
                    "No GitHub token configured (github.token / GITHUB_TOKEN). Required to create "
                            + "release tags, even for a public repository.");
        }
    }

    private GitHubApiException wrap(RestClientResponseException e, String action) {
        return new GitHubApiException(
                "GitHub API call failed (%s): %d %s"
                        .formatted(action, e.getStatusCode().value(), e.getResponseBodyAsString()),
                e);
    }

    private record RepoInfo(@JsonProperty("default_branch") String defaultBranch) {
    }

    private record GitRefResponse(String ref, GitObject object) {
    }

    private record GitObject(String sha, String type) {
    }

    private record CreateRefRequest(String ref, String sha) {
    }
}
