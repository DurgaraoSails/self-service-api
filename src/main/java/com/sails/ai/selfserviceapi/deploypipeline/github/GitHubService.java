package com.sails.ai.selfserviceapi.deploypipeline.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
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
    //
    // Both groups are restricted to what GitHub actually allows in a login and a repository name,
    // rather than "anything but a slash". A POC's githubUrl is operator-supplied, and the owner and
    // name parsed out of it are interpolated into the Cloud Build clone step's shell command — a
    // permissive class here is the difference between a rejected URL and a metacharacter reaching
    // bash. Every real repository URL the looser form accepted still parses: dots stay out of the
    // name group, so a trailing ".git" cannot be swallowed by it.
    private static final Pattern REPO_URL_PATTERN =
            Pattern.compile("github\\.com[/:]([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)"
                    + "/([A-Za-z0-9_-]+)(\\.git)?/?$");

    /**
     * GitHub's own maximum for this endpoint, and the cap on how many pages of it to read. 500
     * branches is far past what any POC repository has; the point of the cap is that one
     * pathological repository cannot make an admin opening a dropdown wait on fifty round trips.
     */
    private static final int BRANCH_PAGE_SIZE = 100;
    private static final int MAX_BRANCH_PAGES = 5;

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
        return readRepo(repo).defaultBranch();
    }

    /**
     * One place builds the repository read, for both the callers that want its default branch and
     * the one that wants its permissions.
     *
     * <p>They still make a call each, and deliberately: {@code requirePushAccess} runs in the async
     * pipeline while the branch is resolved synchronously before the deployment row exists, so
     * there is no single response the two could share without moving one of them into the other's
     * phase. Sharing the request shape is what is available here, and it is the half that would
     * otherwise drift.
     */
    private RepoInfo readRepo(GitHubRepoRef repo) {
        return get("/repos/{owner}/{repo}", RepoInfo.class, repo.owner(), repo.name());
    }

    /**
     * The branch is concatenated into the URI template rather than passed as a template variable,
     * and that is the whole point: a variable's {@code /} is encoded to {@code %2F}, which matches
     * no ref at all, so every slashed branch name ({@code release/2024}) would 404 as though it did
     * not exist. As template text the slash survives, while anything a path segment may not carry
     * is still encoded. {@code PipelineProperties} rejects a configured branch that could abuse
     * that position, and a default branch read back from GitHub is already a valid ref name.
     */
    public String getBranchHeadSha(GitHubRepoRef repo, String branch) {
        return get("/repos/{owner}/{repo}/git/ref/heads/" + branch, GitRefResponse.class,
                repo.owner(), repo.name()).object().sha();
    }

    /**
     * The commit a new version is cut from, and therefore the commit the release tag will point at.
     *
     * <p>Three sources, most specific first:
     *
     * <ol>
     *   <li>{@code pocDeployBranch} — the branch on the POC itself, set in the admin form. This is
     *       the one that should normally decide it: which branch a repository releases from is a
     *       fact about that repository, and one POC releasing from {@code main} says nothing about
     *       the next.</li>
     *   <li>{@code pipeline.deploy-branch} — a platform-wide pin, for a deployment that must cut
     *       every release from one branch name regardless of what each repository prefers. Unset by
     *       default; it applies only to POCs that named no branch of their own.</li>
     *   <li>The repository's own default branch, whatever GitHub reports it to be. The forgiving
     *       option, and the behaviour every POC had before either setting existed.</li>
     * </ol>
     *
     * <p>A branch that was asked for by name and does not exist is a configuration error worth
     * naming — the message says which of the two settings chose it, because the fix is in a
     * different place for each.
     */
    public String getDeployBranchHeadSha(GitHubRepoRef repo, String pocDeployBranch) {
        String branch;
        String remedy;
        if (pocDeployBranch != null && !pocDeployBranch.isBlank()) {
            branch = pocDeployBranch.trim();
            remedy = "This POC's deployBranch asks for that branch, so create it, point the POC at a branch "
                    + "that exists, or clear deployBranch to deploy from the repository's own default branch.";
        } else if (properties.hasDeployBranch()) {
            branch = properties.deployBranch();
            remedy = "pipeline.deploy-branch pins every POC that names no branch of its own to that branch, so "
                    + "create it, give this POC its own deployBranch, or clear pipeline.deploy-branch to deploy "
                    + "each repository from its own default branch.";
        } else {
            return getBranchHeadSha(repo, getDefaultBranch(repo));
        }

        try {
            return getBranchHeadSha(repo, branch);
        } catch (GitHubApiException e) {
            if (!isNotFound(e)) {
                throw e;
            }
            throw new GitHubApiException(
                    "Branch '" + branch + "' does not exist in " + repo + ". " + remedy, e);
        }
    }

    /**
     * A 404 that {@link #get} has already wrapped. Only the pinned-branch path needs to tell that
     * case apart from any other GitHub failure, and unwrapping it there is worth keeping one
     * implementation of "read a branch's head" instead of a second copy that can drift from it.
     */
    private static boolean isNotFound(GitHubApiException e) {
        return e.getCause() instanceof RestClientResponseException cause
                && cause.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND);
    }

    /**
     * Every branch in the repository, for the admin form's deploy-branch picker. Alphabetical, as
     * GitHub returns them — which branch is the default is a separate question, answered by
     * {@link #getDefaultBranch}.
     *
     * <p>Paged rather than assumed to fit: the endpoint returns at most 100 at a time, so a
     * repository with more would otherwise silently lose the branches past the first hundred from
     * the only UI that offers a choice. The read stops early on a short page (the normal case, one
     * call) and gives up at {@link #MAX_BRANCH_PAGES}, reporting {@code truncated} so the caller can
     * say so rather than present a partial list as complete.
     */
    public GitHubBranches listBranches(GitHubRepoRef repo) {
        List<String> names = new ArrayList<>();

        for (int page = 1; page <= MAX_BRANCH_PAGES; page++) {
            BranchRef[] batch = get("/repos/{owner}/{repo}/branches?per_page={perPage}&page={page}",
                    BranchRef[].class, repo.owner(), repo.name(), BRANCH_PAGE_SIZE, page);

            if (batch == null || batch.length == 0) {
                return new GitHubBranches(names, false);
            }
            for (BranchRef branch : batch) {
                names.add(branch.name());
            }
            // A short page is the last page. A full one on the final iteration means there may be
            // more we chose not to read — the only case that is genuinely truncated.
            if (batch.length < BRANCH_PAGE_SIZE) {
                return new GitHubBranches(names, false);
            }
        }

        return new GitHubBranches(names, true);
    }

    /**
     * Reads one file's content at a specific commit — used to read a POC's optional poc.yaml at
     * the exact commit being deployed, never at a moving branch head. Empty when the file doesn't
     * exist at that commit, which is what lets {@code ManifestService} tell "no poc.yaml" apart
     * from a real GitHub failure.
     */
    public Optional<String> getFileContent(GitHubRepoRef repo, String ref, String path) {
        requireToken();
        try {
            ContentResponse response = gitHubRestClient.get()
                    .uri("/repos/{owner}/{repo}/contents/{path}?ref={ref}", repo.owner(), repo.name(), path, ref)
                    .retrieve()
                    .body(ContentResponse.class);
            if (response == null || response.content() == null) {
                return Optional.empty();
            }
            byte[] decoded = Base64.getMimeDecoder().decode(response.content());
            return Optional.of(new String(decoded, StandardCharsets.UTF_8));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                return Optional.empty();
            }
            throw wrap(e, "GET contents of " + path + " on " + repo + " at " + ref);
        }
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
     * Refuses a deploy the configured token could never finish, before it starts one.
     *
     * <p>A POC may point at any GitHub URL, including a public repository owned by someone else.
     * Public visibility grants read to everyone, so the clone would succeed — but this pipeline
     * creates a release tag first, and a tag is a write. Without this check the deploy fails
     * several steps later with GitHub's raw rejection ("create tag 1.0.0 on owner/repo: 403 {...}"),
     * which is accurate and says nothing about the cause. The distinction between "readable" and
     * "writable" is exactly the thing that surprises people here, so the message names it.
     *
     * <p>Deliberately its own call rather than folded into {@link #getDefaultBranch}, which already
     * reads this endpoint: a permission check hidden inside a method named for something else is
     * what a later reader deletes as a redundant round trip.
     */
    public void requirePushAccess(GitHubRepoRef repo) {
        RepoAccess access = checkPushAccess(repo);
        if (access != RepoAccess.OK) {
            throw new GitHubApiException(access.describe(repo));
        }
    }

    /**
     * The same three preconditions {@link #requirePushAccess} enforces, reported instead of
     * thrown, so the onboarding checker can list every problem with a repository in one pass
     * rather than surfacing whichever one happened to throw first.
     *
     * <p>Split out rather than duplicated deliberately: a second implementation of "can we deploy
     * this repo" is exactly how a self-service checker starts telling teams their repo is fine
     * while the pipeline refuses it. The messages live on {@link RepoAccess}, so both callers say
     * the same thing.
     */
    public RepoAccess checkPushAccess(GitHubRepoRef repo) {
        RepoInfo info;
        try {
            info = readRepo(repo);
        } catch (GitHubApiException e) {
            if (!isNotFound(e)) {
                throw e;
            }
            // 404 rather than 403 is also what GitHub returns for a private repo the token cannot
            // see — it does not confirm existence to a caller who may not look. Logged because
            // returning an enum drops GitHub's own response body, and that body is the only thing
            // separating "wrong URL" from "the token cannot see it".
            log.debug("GitHub reported 404 for {}: {}", repo, e.getMessage());
            return RepoAccess.NOT_FOUND;
        }

        // Before the push check, not after: archiving freezes a repository read-only for everyone,
        // so it still reports push: true while rejecting every write. Reporting it as a permission
        // problem would send an admin to change a role that was never the cause.
        if (info != null && Boolean.TRUE.equals(info.archived())) {
            return RepoAccess.ARCHIVED;
        }

        if (info == null || info.permissions() == null || !info.permissions().push()) {
            return RepoAccess.NO_PUSH;
        }

        return RepoAccess.OK;
    }

    /** Why a repository can or cannot be deployed, with the one message both callers use. */
    public enum RepoAccess {

        OK,

        NOT_FOUND,

        ARCHIVED,

        NO_PUSH;

        public String describe(GitHubRepoRef repo) {
            return switch (this) {
                case OK -> "GitHub repository " + repo + " is reachable and writable.";
                case NOT_FOUND -> "GitHub repository " + repo + " was not found, or is not visible to the "
                        + "configured token. Check the POC's GitHub URL for a typo, and that the token has access "
                        + "if the repository is private.";
                case ARCHIVED -> "GitHub repository " + repo + " is archived, so it is read-only and no "
                        + "release tag can be created on it. Unarchive it, or point this POC at an active repository.";
                case NO_PUSH -> "The configured GitHub token cannot push to " + repo + ", so it cannot "
                        + "create the release tag this deploy needs. Note that a public repository is readable by "
                        + "anyone but still only writable by its collaborators — point this POC at a repository the "
                        + "token has write access to, or configure a token that does.";
            };
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

    /**
     * {@code permissions} is returned only for an authenticated caller, which this always is.
     *
     * <p>{@code archived} is boxed rather than a primitive: GitHub always sends it, but a record
     * component of type {@code boolean} makes the field mandatory to bind, so any response without
     * it fails deserialization outright instead of defaulting to false. Absent reads as "not
     * archived" here, which is both the safe direction and the one that keeps a trimmed response
     * from failing a deploy for the wrong reason.
     */
    private record RepoInfo(@JsonProperty("default_branch") String defaultBranch, Permissions permissions,
                             Boolean archived) {
    }

    /**
     * Only {@code push} is read. {@code admin}/{@code pull} are also returned; pull adds nothing
     * (a repo that could not be read would have 404'd) and admin is more than a tag requires.
     */
    private record Permissions(boolean push) {
    }

    private record BranchRef(String name) {
    }

    private record GitRefResponse(String ref, GitObject object) {
    }

    private record GitObject(String sha, String type) {
    }

    private record CreateRefRequest(String ref, String sha) {
    }

    private record ContentResponse(String content, String encoding) {
    }
}
