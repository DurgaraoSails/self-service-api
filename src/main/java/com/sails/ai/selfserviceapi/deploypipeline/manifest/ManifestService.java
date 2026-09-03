package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import org.springframework.stereotype.Service;

/**
 * Resolves the manifest for one deploy attempt — either poc.yaml at the repo's current commit, or
 * the synthesized single-container default when the repo has none.
 */
@Service
public class ManifestService {

    private static final String MANIFEST_PATH = "poc.yaml";

    private final GitHubService gitHubService;
    private final ManifestParser parser;

    public ManifestService(GitHubService gitHubService, ManifestParser parser) {
        this.gitHubService = gitHubService;
        this.parser = parser;
    }

    /**
     * Fetches poc.yaml from GitHub at the given commit. Absent means: one container, root
     * Dockerfile. {@link Resolved#rawYaml()} is null in that case too — there's nothing to store
     * beyond "this version used the default," which {@link #resolveStored} already reconstructs
     * from null without needing a serialized form of the synthetic manifest.
     */
    public Resolved resolve(GitHubRepoRef repo, String commitSha) {
        return gitHubService.tryGetFileContent(repo, MANIFEST_PATH, commitSha)
                .map(rawYaml -> new Resolved(parser.parse(rawYaml), rawYaml))
                .orElseGet(() -> new Resolved(PocManifest.defaultSingleContainer(), null));
    }

    /**
     * Reconstructs the manifest a version was actually built with, from what {@code reportStatus}
     * stored on {@code poc_versions.manifest_yaml} — used by redeploy, which must never re-read
     * whatever poc.yaml says in the repo today. Null/blank (every pre-manifest version, and every
     * version that never had a real poc.yaml) means the same single-container default the
     * original deploy resolved to.
     */
    public PocManifest resolveStored(String manifestYaml) {
        return manifestYaml == null || manifestYaml.isBlank()
                ? PocManifest.defaultSingleContainer()
                : parser.parse(manifestYaml);
    }

    /** {@code rawYaml} is exactly what's persisted to {@code poc_versions.manifest_yaml}. */
    public record Resolved(PocManifest manifest, String rawYaml) {
    }
}
