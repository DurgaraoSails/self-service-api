package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a {@code docker build}/{@code docker buildx build} or {@code kaniko} command's words into
 * an {@link ImportedBuild} — the Dockerfile, context, and image reference(s) it produces, which
 * {@code CloudBuildImporter} later links to a {@code gcloud run deploy --image}.
 */
public class ImageBuildParser {

    public record Result(ImportedBuild build, List<GenerationNotice> notices) {
    }

    /** {@code words} starts with {@code "docker"} (optionally {@code "buildx"}) then {@code "build"}. Empty if this isn't that shape. */
    public java.util.Optional<Result> parseDockerBuild(List<String> words, String stepDir) {
        int i = matchPrefix(words, "docker", "build");
        if (i < 0) {
            i = matchPrefix(words, "docker", "buildx", "build");
        }
        if (i < 0) {
            return java.util.Optional.empty();
        }

        String dockerfile = null;
        String context = null;
        List<String> tags = new ArrayList<>();
        List<String> buildArgNames = new ArrayList<>();
        String target = null;

        for (; i < words.size(); i++) {
            String[] flag = splitFlagEquals(words.get(i));
            switch (flag[0]) {
                case "-f", "--file" -> dockerfile = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                case "-t", "--tag" -> {
                    String tag = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                    if (tag != null) {
                        tags.add(tag);
                    }
                }
                case "--build-arg" -> {
                    String arg = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                    if (arg != null) {
                        int eq = arg.indexOf('=');
                        buildArgNames.add(eq > 0 ? arg.substring(0, eq) : arg);
                    }
                }
                case "--target" -> target = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                default -> {
                    if (!words.get(i).startsWith("-")) {
                        context = words.get(i);
                    }
                }
            }
        }

        String resolvedContext = resolvePath(stepDir, context != null ? context : ".");
        String resolvedDockerfile = dockerfile != null
                ? resolvePath(stepDir, dockerfile)
                : resolvePath(resolvedContext, "Dockerfile");

        List<GenerationNotice> notices = new ArrayList<>();
        if (!buildArgNames.isEmpty()) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.BUILD_ARG_UNSUPPORTED,
                    "This build passes --build-arg (" + String.join(", ", buildArgNames) + "); the generated "
                            + "Dockerfile does not receive build args, since values may be substitution- or "
                            + "secret-derived. Add an ARG with a safe default if your Dockerfile needs one."));
        }
        if (target != null) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.BUILD_TARGET_UNSUPPORTED,
                    "This build pins --target=" + target + "; the platform always builds the Dockerfile's "
                            + "final stage."));
        }

        return java.util.Optional.of(new Result(
                new ImportedBuild(resolvedDockerfile, resolvedContext, tags, buildArgNames, target), notices));
    }

    /** {@code words} are kaniko's flags directly — no leading subcommand word. */
    public java.util.Optional<Result> parseKaniko(List<String> words, String stepDir) {
        if (words.isEmpty()) {
            return java.util.Optional.empty();
        }
        String dockerfile = null;
        String context = null;
        List<String> destinations = new ArrayList<>();

        for (int i = 0; i < words.size(); i++) {
            String[] flag = splitFlagEquals(words.get(i));
            switch (flag[0]) {
                case "--dockerfile" -> dockerfile = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                case "--context" -> {
                    String raw = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                    context = raw != null && raw.startsWith("dir://") ? raw.substring("dir://".length()) : raw;
                }
                case "--destination" -> {
                    String dest = flag[1] != null ? flag[1] : nextOrNull(words, ++i);
                    if (dest != null) {
                        destinations.add(dest);
                    }
                }
                default -> {
                    // kaniko has many other flags (--cache, --verbosity, ...); none of them affect
                    // what this platform needs, so they are silently ignored rather than flagged.
                }
            }
        }

        String resolvedContext = resolvePath(stepDir, context != null ? context : ".");
        String resolvedDockerfile = dockerfile != null
                ? resolvePath(stepDir, dockerfile)
                : resolvePath(resolvedContext, "Dockerfile");

        return java.util.Optional.of(new Result(
                new ImportedBuild(resolvedDockerfile, resolvedContext, destinations, List.of(), null), List.of()));
    }

    public GenerationNotice buildpacksNeedsDockerfileNotice() {
        return GenerationNotice.info(GenerationNoticeCode.BUILDPACKS_NEEDS_DOCKERFILE,
                "This repository builds with Cloud Native Buildpacks (pack build / gcloud builds submit --pack / "
                        + "gcloud run deploy --source with no Dockerfile), which this platform does not run — it "
                        + "always builds a Dockerfile with 'docker build'. A Dockerfile is generated for review "
                        + "instead.");
    }

    private int matchPrefix(List<String> words, String... prefix) {
        if (words.size() < prefix.length) {
            return -1;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (!words.get(i).equals(prefix[i])) {
                return -1;
            }
        }
        return prefix.length;
    }

    private String[] splitFlagEquals(String word) {
        if (!word.startsWith("-")) {
            return new String[]{word, null};
        }
        int eq = word.indexOf('=');
        return eq < 0 ? new String[]{word, null} : new String[]{word.substring(0, eq), word.substring(eq + 1)};
    }

    private String nextOrNull(List<String> words, int index) {
        return index < words.size() ? words.get(index) : null;
    }

    /** A step's {@code dir:} is prefixed onto its relative paths. */
    private String resolvePath(String dir, String path) {
        if (path == null) {
            return dir == null || dir.isBlank() ? "." : dir;
        }
        String cleanedPath = path.startsWith("./") ? path.substring(2) : path;
        if (dir == null || dir.isBlank() || dir.equals(".")) {
            return cleanedPath.isEmpty() ? "." : cleanedPath;
        }
        return cleanedPath.equals(".") ? dir : dir + "/" + cleanedPath;
    }
}
