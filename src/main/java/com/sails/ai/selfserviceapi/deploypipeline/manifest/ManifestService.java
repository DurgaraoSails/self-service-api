package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The one entry point for turning "a POC repo" into a {@link PocManifest} — every other class in
 * this package (parsing, validation) is a private implementation detail reached only through here.
 *
 * <p>Two distinct paths, matching the two things a manifest is used for: {@link #resolveForBuild}
 * reads and validates poc.yaml fresh from GitHub, for a brand new build; {@link #resolveStored}
 * re-parses a version's already-stored manifest text, for redeploy/rollback, which must never
 * change what it deploys just because the repo's poc.yaml changed since that version was built.
 */
@Service
public class ManifestService {

    /** Every container this phase builds comes from the primary repo — see ManifestContainer's javadoc. */
    private static final String DEFAULT_CONTAINER_NAME = "app";

    /**
     * The filenames a manifest may use, tried in this order. Both spellings are accepted because
     * only one used to be: a repo with {@code poc.yml} silently fell through to the synthesized
     * single-container default, so its manifest appeared to be ignored for no reason its author
     * could discover. Public because {@code PocOnboardingCheckService} reports the same names back
     * to a team, and two private copies of one filename are how that message drifts.
     *
     * <p>Two spellings, not an open set: this is a typo guard, not an invitation to invent names.
     */
    public static final List<String> MANIFEST_PATHS = List.of("poc.yaml", "poc.yml");

    private final GitHubService gitHubService;
    private final ManifestParser parser;
    private final ManifestValidator validator;

    public ManifestService(GitHubService gitHubService, ManifestParser parser, ManifestValidator validator) {
        this.gitHubService = gitHubService;
        this.parser = parser;
        this.validator = validator;
    }

    /**
     * Reads the first of {@link #MANIFEST_PATHS} the repo has at the given commit, validates it, and
     * fails before anything is cloned or built if it is invalid. A repo with neither resolves to the
     * synthesized single-container default — today's implicit behavior, made explicit.
     */
    public ManifestResolution resolveForBuild(GitHubRepoRef repo, String ref) {
        for (String path : MANIFEST_PATHS) {
            Optional<String> rawYaml = gitHubService.getFileContent(repo, ref, path);
            if (rawYaml.isEmpty()) {
                continue;
            }
            PocManifest manifest = parser.parse(rawYaml.get());
            List<String> violations = validator.validate(manifest);
            if (!violations.isEmpty()) {
                throw new ManifestValidationException(violations);
            }
            return new ManifestResolution(rawYaml.get(), manifest);
        }
        return new ManifestResolution(null, synthesizeDefault());
    }

    /**
     * Parses a version's stored manifest text — never touches GitHub. Null/blank (every
     * pre-manifest version, and any version whose repo simply had no poc.yaml) resolves to the
     * same synthesized default {@link #resolveForBuild} would have used at the time.
     */
    public PocManifest resolveStored(String manifestYaml) {
        if (manifestYaml == null || manifestYaml.isBlank()) {
            return synthesizeDefault();
        }
        return parser.parse(manifestYaml);
    }

    private PocManifest synthesizeDefault() {
        ManifestContainer app = new ManifestContainer(
                DEFAULT_CONTAINER_NAME, ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        return new PocManifest(List.of(app), new Resources(null, null));
    }
}
