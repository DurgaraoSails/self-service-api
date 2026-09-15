package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What a repository's tree actually contains, shaped for everything downstream that decides
 * containers and Dockerfiles — a pure function of {@link GitHubTree}, so it is exercised with
 * plain fixtures rather than a mocked {@code GitHubService}.
 *
 * <p>Vendor and build-output directories are excluded everywhere here, the same set
 * {@code RepoInventoryService} already skips as evidence — a checked-in {@code node_modules} must
 * never contribute a "Dockerfile" or a stack marker any more than it contributes raw evidence text.
 *
 * @param dockerfiles                every {@code Dockerfile}/{@code Dockerfile.*}/{@code *.Dockerfile}/
 *                                   {@code Containerfile} path.
 * @param directoriesWithDockerignore directories (root as {@code ""}) that already have a {@code .dockerignore}.
 * @param cloudbuildFiles            every {@code cloudbuild*.y(a)ml}/{@code *.cloudbuild.y(a)ml}/{@code cloudbuild.json}
 *                                   path, read by {@code CloudBuildImporter}.
 * @param composeFiles               every {@code docker-compose.y(a)ml}/{@code compose.y(a)ml} path.
 * @param basenamesByDirectory       every file's basename, grouped by its directory (root as
 *                                   {@code ""}), for directories at most 4 segments deep — the raw
 *                                   material {@code StackDetector} reads markers from, so both stay
 *                                   in sync with what a directory actually contains without a
 *                                   second, hand-maintained list of "interesting" basenames.
 */
public record RepoLayout(
        List<String> dockerfiles,
        Set<String> directoriesWithDockerignore,
        List<String> cloudbuildFiles,
        List<String> composeFiles,
        Map<String, Set<String>> basenamesByDirectory
) {

    private static final Set<String> VENDOR_DIRECTORIES = Set.of(
            "node_modules", "vendor", "dist", "build", "target", ".venv", "venv", "__pycache__", ".next");

    private static final int MAX_MARKER_DEPTH = 4;

    public static RepoLayout of(GitHubTree tree) {
        List<String> dockerfiles = new ArrayList<>();
        Set<String> dockerignoreDirs = new LinkedHashSet<>();
        List<String> cloudbuildFiles = new ArrayList<>();
        List<String> composeFiles = new ArrayList<>();
        Map<String, Set<String>> basenamesByDirectory = new LinkedHashMap<>();

        for (GitHubTreeEntry entry : tree.entries()) {
            if (!entry.isBlob() || isUnderVendorDirectory(entry.path())) {
                continue;
            }
            String path = entry.path();
            String directory = directoryOf(path);
            String basename = basenameOf(path);
            String lower = basename.toLowerCase();

            if (isDockerfile(lower)) {
                dockerfiles.add(path);
            }
            if (lower.equals(".dockerignore")) {
                dockerignoreDirs.add(directory);
            }
            if (isCloudbuildFile(lower)) {
                cloudbuildFiles.add(path);
            }
            if (isComposeFile(lower)) {
                composeFiles.add(path);
            }
            if (depthOf(directory) <= MAX_MARKER_DEPTH) {
                basenamesByDirectory.computeIfAbsent(directory, d -> new LinkedHashSet<>()).add(basename);
            }
        }

        return new RepoLayout(dockerfiles, dockerignoreDirs, cloudbuildFiles, composeFiles, basenamesByDirectory);
    }

    /** Every directory that has at least one Dockerfile, root as {@code ""}. */
    public Set<String> directoriesWithDockerfile() {
        Set<String> directories = new LinkedHashSet<>();
        for (String dockerfile : dockerfiles) {
            directories.add(directoryOf(dockerfile));
        }
        return directories;
    }

    /** Basenames present in exactly this directory ({@code ""} for root). Never null. */
    public Set<String> basenamesIn(String directory) {
        return basenamesByDirectory.getOrDefault(directory, Set.of());
    }

    private static boolean isDockerfile(String lowerBasename) {
        return lowerBasename.equals("dockerfile") || lowerBasename.startsWith("dockerfile.")
                || lowerBasename.endsWith(".dockerfile") || lowerBasename.equals("containerfile");
    }

    private static boolean isCloudbuildFile(String lowerBasename) {
        if (lowerBasename.equals("cloudbuild.json")) {
            return true;
        }
        boolean yaml = lowerBasename.endsWith(".yaml") || lowerBasename.endsWith(".yml");
        if (!yaml) {
            return false;
        }
        return lowerBasename.startsWith("cloudbuild") || lowerBasename.endsWith(".cloudbuild.yaml")
                || lowerBasename.endsWith(".cloudbuild.yml");
    }

    private static boolean isComposeFile(String lowerBasename) {
        return lowerBasename.equals("docker-compose.yml") || lowerBasename.equals("docker-compose.yaml")
                || lowerBasename.equals("compose.yml") || lowerBasename.equals("compose.yaml");
    }

    private static boolean isUnderVendorDirectory(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (VENDOR_DIRECTORIES.contains(segments[i])) {
                return true;
            }
        }
        return false;
    }

    private static String directoryOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String basenameOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static int depthOf(String directory) {
        return directory.isEmpty() ? 0 : directory.split("/").length;
    }
}
