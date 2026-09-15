package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A per-request cache over {@link GitHubService#getFileContent}, shared by whatever in one
 * generation run needs to read specific files by path — {@code StackDetector}, {@code CloudBuildImporter}
 * and {@code DockerfileLinter} between them can easily ask for the same {@code package.json} or
 * Dockerfile more than once. One instance per {@code (repo, sha)}, created fresh for each
 * {@code /poc-onboarding/generate} call — never a shared Spring bean, since the cache would
 * otherwise leak across unrelated repositories and requests.
 *
 * <p>The budget here is independent of {@code RepoInventoryService}'s own cap on evidence shown to
 * a model: this reader serves deterministic code that never sends a byte to an LLM, so its limit
 * exists only to bound how much of one generation run a single pathological repository can spend
 * reading files nobody will use — not to protect a context window.
 */
public class RepoFileReader {

    private static final int DEFAULT_MAX_FILES = 40;
    private static final long DEFAULT_MAX_BYTES = 500_000;

    private final GitHubService gitHubService;
    private final GitHubRepoRef repo;
    private final String sha;
    private final int maxFiles;
    private final long maxBytes;

    private final Map<String, Optional<String>> cache = new HashMap<>();
    private int filesFetched = 0;
    private long bytesFetched = 0;

    public RepoFileReader(GitHubService gitHubService, GitHubRepoRef repo, String sha) {
        this(gitHubService, repo, sha, DEFAULT_MAX_FILES, DEFAULT_MAX_BYTES);
    }

    public RepoFileReader(GitHubService gitHubService, GitHubRepoRef repo, String sha, int maxFiles, long maxBytes) {
        this.gitHubService = gitHubService;
        this.repo = repo;
        this.sha = sha;
        this.maxFiles = maxFiles;
        this.maxBytes = maxBytes;
    }

    /**
     * The file's content, or empty if it doesn't exist, could not be read, or the budget for this
     * request was already spent. A budget cutoff is silent by design — every caller already treats
     * "not found" and "not read" the same way (skip this piece of evidence), so a distinct signal
     * for the cutoff would add a state nothing acts on differently.
     */
    public Optional<String> read(String path) {
        if (cache.containsKey(path)) {
            return cache.get(path);
        }
        if (filesFetched >= maxFiles || bytesFetched >= maxBytes) {
            return Optional.empty();
        }
        Optional<String> content = gitHubService.getFileContent(repo, sha, path);
        cache.put(path, content);
        filesFetched++;
        content.ifPresent(c -> bytesFetched += c.length());
        return content;
    }

    public boolean exists(String path) {
        return read(path).isPresent();
    }
}
