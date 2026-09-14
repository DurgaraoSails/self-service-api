package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoInventory.EvidenceFile;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Turns a repository's tree into the bounded set of files a draft model is actually shown.
 *
 * <p>The cap here is not primarily about cost — it is what keeps a generation request inside the
 * context window of whatever model is configured, including a modest local one. It works alongside
 * {@code DraftModelProperties.Ollama#numCtx()}, not instead of it: the two are independent guards
 * against the same failure (an LLM confidently drafting a manifest from a repository it only
 * partially saw), and either alone would leave a gap the other closes.
 */
@Service
public class RepoInventoryService {

    /** Modest on purpose — see the class javadoc. Twelve files is generous for what a manifest needs. */
    static final int MAX_FILES = 12;

    /** Independent of {@code numCtx}: a byte cap here can never be undone by a larger context setting. */
    static final int MAX_TOTAL_BYTES = 200_000;

    /** A single file this large would alone consume most of the budget — skipped rather than crowding everything else out. */
    static final long MAX_SINGLE_FILE_BYTES = 60_000;

    private static final Set<String> DEPENDENCY_MANIFEST_BASENAMES = Set.of(
            "package.json", "requirements.txt", "pyproject.toml", "pom.xml", "go.mod",
            "build.gradle", "build.gradle.kts", "cargo.toml", "gemfile", "composer.json");

    private static final Set<String> COMPOSE_BASENAMES = Set.of("docker-compose.yml", "docker-compose.yaml");

    private static final Set<String> ENTRYPOINT_BASENAMES = Set.of(
            "main.py", "app.py", "index.js", "index.ts", "server.js", "server.ts", "main.go", "program.cs");

    private final GitHubService gitHubService;

    public RepoInventoryService(GitHubService gitHubService) {
        this.gitHubService = gitHubService;
    }

    public RepoInventory inventory(GitHubRepoRef repo, String sha) {
        GitHubTree tree = gitHubService.listTree(repo, sha);
        List<GitHubTreeEntry> ranked = rankEvidence(tree);

        List<EvidenceFile> files = new ArrayList<>();
        long totalBytes = 0;
        for (GitHubTreeEntry entry : ranked) {
            if (files.size() >= MAX_FILES) {
                break;
            }
            if (entry.size() != null && entry.size() > MAX_SINGLE_FILE_BYTES) {
                continue;
            }
            String content = gitHubService.getFileContent(repo, sha, entry.path()).orElse(null);
            if (content == null) {
                continue;
            }
            if (totalBytes + content.length() > MAX_TOTAL_BYTES) {
                continue;
            }
            files.add(new EvidenceFile(entry.path(), content));
            totalBytes += content.length();
        }

        return new RepoInventory(tree, files, tree.truncated());
    }

    /**
     * Priority order, highest first: every Dockerfile decides the container shape directly;
     * dependency manifests and compose files are the next-strongest signal; a root README and
     * common entrypoints round it out. Not a hard cutoff — the caller still enforces the file/byte
     * caps — this just decides which files are worth spending that budget on first.
     */
    private List<GitHubTreeEntry> rankEvidence(GitHubTree tree) {
        List<GitHubTreeEntry> dockerfiles = new ArrayList<>();
        List<GitHubTreeEntry> manifest = new ArrayList<>();
        List<GitHubTreeEntry> compose = new ArrayList<>();
        List<GitHubTreeEntry> readme = new ArrayList<>();
        List<GitHubTreeEntry> entrypoints = new ArrayList<>();

        for (GitHubTreeEntry entry : tree.entries()) {
            if (!entry.isBlob()) {
                continue;
            }
            String basename = basename(entry.path());
            String lower = basename.toLowerCase();
            if (lower.equals("dockerfile") || lower.startsWith("dockerfile.")) {
                dockerfiles.add(entry);
            } else if (lower.equals("poc.yaml") || lower.equals("poc.yml")) {
                // An existing manifest is the strongest signal of all — always first.
                manifest.add(0, entry);
            } else if (DEPENDENCY_MANIFEST_BASENAMES.contains(lower)) {
                manifest.add(entry);
            } else if (COMPOSE_BASENAMES.contains(lower)) {
                compose.add(entry);
            } else if (lower.equals("readme.md") || lower.equals("readme")) {
                readme.add(entry);
            } else if (ENTRYPOINT_BASENAMES.contains(lower)) {
                entrypoints.add(entry);
            }
        }

        List<GitHubTreeEntry> ranked = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (List<GitHubTreeEntry> group : List.of(dockerfiles, manifest, compose, readme, entrypoints)) {
            for (GitHubTreeEntry entry : group) {
                if (seen.add(entry.path())) {
                    ranked.add(entry);
                }
            }
        }
        return ranked;
    }

    private String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
