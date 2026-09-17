package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildConfigParser.Step;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns one cloudbuild.yaml step into the shell commands it actually runs, tagged with which tool
 * each command invokes. A step is either a script (its own {@code script:} field, or an
 * {@code entrypoint: bash}/{@code sh} with {@code -c}) — parsed with {@link ShellWords}, never
 * executed — or a plain {@code args:} invocation, where the tool comes from {@code entrypoint} or
 * the builder image named in {@code name}.
 */
public class StepCommandExtractor {

    public enum Tool {
        DOCKER, GCLOUD, KANIKO, PACK, PRE_BUILD, UNKNOWN
    }

    public record ExtractedCommand(Tool tool, List<String> words, String dir) {
    }

    private static final Set<String> SHELL_ENTRYPOINTS = Set.of("bash", "sh", "/bin/bash", "/bin/sh");

    public List<ExtractedCommand> extract(Step step, Map<String, String> optionsEnv) {
        Map<String, String> env = new LinkedHashMap<>(optionsEnv);
        env.putAll(step.env());

        String scriptText = scriptTextOf(step);
        if (scriptText != null) {
            ShellWords.ParsedScript parsed = ShellWords.parse(scriptText, env);
            List<ExtractedCommand> commands = new ArrayList<>();
            for (List<String> words : parsed.commands()) {
                commands.add(new ExtractedCommand(toolFor(words.get(0), null), words, step.dir()));
            }
            return commands;
        }

        List<String> words = new ArrayList<>();
        if (step.entrypoint() != null && !step.entrypoint().isBlank()) {
            words.add(step.entrypoint());
        }
        words.addAll(step.args());
        if (words.isEmpty()) {
            return List.of();
        }
        Tool tool = step.entrypoint() != null && !step.entrypoint().isBlank()
                ? toolFor(step.entrypoint(), step.name())
                : toolForBuilderImage(step.name());
        // kaniko's args ARE its flags directly (no leading "kaniko" subcommand word to strip), so
        // its word list is exactly step.args() with no entrypoint prefix — words already holds this
        // when entrypoint is unset, which is kaniko's normal shape.
        return List.of(new ExtractedCommand(tool, words, step.dir()));
    }

    private String scriptTextOf(Step step) {
        if (step.script() != null && !step.script().isBlank()) {
            return step.script();
        }
        String entrypoint = step.entrypoint();
        boolean isShellEntrypoint = entrypoint != null && SHELL_ENTRYPOINTS.contains(baseName(entrypoint));
        if (isShellEntrypoint && step.args().size() >= 2 && step.args().get(0).equals("-c")) {
            return step.args().get(1);
        }
        return null;
    }

    private Tool toolFor(String firstWord, String builderImage) {
        String base = baseName(firstWord);
        return switch (base) {
            case "docker" -> Tool.DOCKER;
            case "gcloud" -> Tool.GCLOUD;
            case "npm", "yarn", "pnpm", "mvn", "mvnw", "gradle", "gradlew", "go", "node", "python", "python3" -> Tool.PRE_BUILD;
            default -> builderImage != null ? toolForBuilderImage(builderImage) : Tool.UNKNOWN;
        };
    }

    private Tool toolForBuilderImage(String image) {
        if (image == null) {
            return Tool.UNKNOWN;
        }
        String lower = image.toLowerCase(Locale.ROOT);
        if (lower.contains("cloud-builders/docker")) {
            return Tool.DOCKER;
        }
        if (lower.contains("cloud-builders/gcloud") || lower.contains("cloudsdktool/") || lower.contains("google/cloud-sdk")) {
            return Tool.GCLOUD;
        }
        if (lower.contains("kaniko-project/executor")) {
            return Tool.KANIKO;
        }
        if (lower.contains("k8s-skaffold/pack") || lower.contains("buildpacksio/pack")) {
            return Tool.PACK;
        }
        if (lower.contains("cloud-builders/npm") || lower.contains("cloud-builders/yarn") || lower.contains("cloud-builders/mvn")
                || lower.contains("cloud-builders/gradle") || lower.startsWith("node:") || lower.contains("/node:")
                || lower.startsWith("maven:") || lower.contains("/maven:") || lower.startsWith("gradle:") || lower.contains("/gradle:")
                || lower.startsWith("golang:") || lower.contains("/golang:") || lower.startsWith("python:") || lower.contains("/python:")) {
            return Tool.PRE_BUILD;
        }
        return Tool.UNKNOWN;
    }

    private String baseName(String pathOrWord) {
        int slash = pathOrWord.lastIndexOf('/');
        return slash < 0 ? pathOrWord : pathOrWord.substring(slash + 1);
    }
}
