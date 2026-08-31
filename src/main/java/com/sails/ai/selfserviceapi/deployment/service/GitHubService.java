package com.sails.ai.selfserviceapi.deployment.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sails.ai.selfserviceapi.deployment.exception.GitHubApiException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class GitHubService {

    // Matches github.com/{owner}/{repo}, with or without a trailing .git or slash,
    // and the git@github.com:{owner}/{repo}.git SSH form.
    private static final Pattern REPO_URL_PATTERN =
            Pattern.compile("github\\.com[/:]([^/]+)/([^/.]+)(\\.git)?/?$");

    private final RestClient gitHubRestClient;

    public GitHubService(RestClient gitHubRestClient) {
        this.gitHubRestClient = gitHubRestClient;
    }

    public GitHubRepoRef parseRepoUrl(String githubUrl) {
        Matcher matcher = REPO_URL_PATTERN.matcher(githubUrl.trim());
        if (!matcher.find()) {
            throw new GitHubApiException("Not a recognizable GitHub repo URL: " + githubUrl);
        }
        return new GitHubRepoRef(matcher.group(1), matcher.group(2));
    }

    public String getDefaultBranch(GitHubRepoRef repo) {
        RepoInfo info = get("/repos/{owner}/{repo}", RepoInfo.class, repo.owner(), repo.name());
        return info.defaultBranch();
    }

    public String getBranchHeadSha(GitHubRepoRef repo, String branch) {
        GitRefResponse ref = get("/repos/{owner}/{repo}/git/ref/heads/{branch}", GitRefResponse.class,
                repo.owner(), repo.name(), branch);
        return ref.object().sha();
    }

    public List<String> listTagNames(GitHubRepoRef repo) {
        TagInfo[] tags = get("/repos/{owner}/{repo}/tags?per_page=100", TagInfo[].class,
                repo.owner(), repo.name());
        return Arrays.stream(tags).map(TagInfo::name).toList();
    }

    /** Creates a lightweight tag ref pointing directly at commitSha — no annotated tag object. */
    public void createTag(GitHubRepoRef repo, String tagName, String commitSha) {
        try {
            gitHubRestClient.post()
                    .uri("/repos/{owner}/{repo}/git/refs", repo.owner(), repo.name())
                    .body(new CreateRefRequest("refs/tags/" + tagName, commitSha))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw wrap(e, "create tag " + tagName + " on " + repo);
        }
    }

    private <T> T get(String uri, Class<T> type, Object... uriVars) {
        try {
            return gitHubRestClient.get().uri(uri, uriVars).retrieve().body(type);
        } catch (RestClientResponseException e) {
            throw wrap(e, "GET " + uri);
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

    private record TagInfo(String name, GitObject commit) {
    }

    private record CreateRefRequest(String ref, String sha) {
    }
}
