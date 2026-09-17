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

        if (step.args().isEmpty() && (step.entrypoint() == null || step.entrypoint().isBlank())) {
            return List.of();
        }

        List<String> words = new ArrayList<>();
        Tool tool;
        if (step.entrypoint() != null && !step.entrypoint().isBlank()) {
            // An explicit entrypoint IS the literal command word — "npm", "mvn", a full path, etc.
            words.add(step.entrypoint());
            tool = toolFor(step.entrypoint(), step.name());
        } else {
            // A plain args: invocation never restates the tool name — that's implied by the builder
            // image itself ('docker build -t x .' is written as just args: ['build','-t','x','.']
            // against gcr.io/cloud-builders/docker). Every downstream parser expects the literal CLI
            // shape ("docker"/"gcloud"/"pack" as the first word), so it is prepended here from the
            // builder image — except kaniko, whose args already ARE its flags with no subcommand
            // word of their own.
            tool = toolForBuilderImage(step.name());
            String canonical = canonicalWordFor(tool);
            if (canonical != null) {
                words.add(canonical);
            }
        }
        words.addAll(step.args());
        if (words.isEmpty()) {
            return List.of();
        }
        return List.of(new ExtractedCommand(tool, words, step.dir()));
    }

    private String canonicalWordFor(Tool tool) {
        return switch (tool) {
            case DOCKER -> "docker";
            case GCLOUD -> "gcloud";
            case PACK -> "pack";
            default -> null;
        };
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
