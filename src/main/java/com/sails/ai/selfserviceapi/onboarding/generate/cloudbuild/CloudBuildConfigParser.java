package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Turns a cloudbuild.yaml/.json's raw text into a plain structural {@link Config} — every step's
 * name/entrypoint/args/script/env/dir, {@code substitutions}, {@code options.env} and top-level
 * {@code images}, with user-defined substitutions already resolved into each string. Nothing here
 * decides what a step *does*; that is {@link StepCommandExtractor}'s job.
 *
 * <p>{@code SafeConstructor} (no arbitrary Java object construction) and a capped
 * {@link LoaderOptions} (alias expansion limited, so a small file cannot YAML-bomb this into
 * exhausting memory) — the same caution any parser of repository-supplied YAML needs, matching
 * {@code ManifestParser}'s own use of {@code Yaml} for poc.yaml. JSON parses as YAML with no
 * special-casing, since YAML is a superset of JSON.
 */
public class CloudBuildConfigParser {

    private static final int MAX_SUBSTITUTION_DEPTH = 5;

    public record Step(String name, String entrypoint, List<String> args, String script,
                        Map<String, String> env, String dir, String id, List<String> waitFor) {
    }

    public record Config(List<Step> steps, Map<String, String> substitutions, Map<String, String> optionsEnv,
                          List<String> images) {
    }

    public Config parse(String yamlText) {
        Object root;
        try {
            root = safeYaml().load(yamlText);
        } catch (YAMLException e) {
            throw new CloudBuildParseException("not valid YAML: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            throw new CloudBuildParseException("top level is not a mapping");
        }

        Map<String, String> userSubstitutions = stringMap(rootMap.get("substitutions"));
        Map<String, String> resolvedSubstitutions = resolveSubstitutions(userSubstitutions);

        Object rawSteps = rootMap.get("steps");
        if (!(rawSteps instanceof List<?> stepList)) {
            throw new CloudBuildParseException("'steps' must be a list");
        }
        List<Step> steps = new ArrayList<>();
        for (Object entry : stepList) {
            if (!(entry instanceof Map<?, ?> stepMap)) {
                throw new CloudBuildParseException("each step must be a mapping");
            }
            steps.add(parseStep(stepMap, resolvedSubstitutions));
        }

        Map<String, String> optionsEnv = envListToMap(stringList(mapAt(rootMap, "options", "env")), resolvedSubstitutions);
        List<String> images = stringList(rootMap.get("images")).stream()
                .map(img -> substitute(img, resolvedSubstitutions))
                .toList();

        return new Config(steps, resolvedSubstitutions, optionsEnv, images);
    }

    private Step parseStep(Map<?, ?> stepMap, Map<String, String> substitutions) {
        String name = substitute(stringOrNull(stepMap.get("name")), substitutions);
        String entrypoint = substitute(stringOrNull(stepMap.get("entrypoint")), substitutions);
        List<String> args = stringList(stepMap.get("args")).stream().map(a -> substitute(a, substitutions)).toList();
        String script = substitute(stringOrNull(stepMap.get("script")), substitutions);
        Map<String, String> env = envListToMap(stringList(stepMap.get("env")), substitutions);
        String dir = substitute(stringOrNull(stepMap.get("dir")), substitutions);
        String id = stringOrNull(stepMap.get("id"));
        List<String> waitFor = stringList(stepMap.get("waitFor"));
        return new Step(name, entrypoint, args, script, env, dir, id, waitFor);
    }

    /**
     * User-defined substitutions ({@code $_X}/{@code ${_X}}) may reference each other, so this
     * resolves up to {@value #MAX_SUBSTITUTION_DEPTH} levels deep. Anything left unresolved after
     * that — a built-in like {@code $PROJECT_ID}, or a genuine cycle — is simply not in the map
     * {@link #substitute} draws from, so it passes through as literal, unresolved text everywhere
     * else, exactly like a built-in does.
     */
    private Map<String, String> resolveSubstitutions(Map<String, String> raw) {
        Map<String, String> resolved = new LinkedHashMap<>(raw);
        for (int pass = 0; pass < MAX_SUBSTITUTION_DEPTH; pass++) {
            boolean changed = false;
            for (Map.Entry<String, String> entry : resolved.entrySet()) {
                String before = entry.getValue();
                String after = substitute(before, resolved);
                if (!after.equals(before)) {
                    entry.setValue(after);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return resolved;
    }

    /** {@code $$} means a literal {@code $}; {@code $_NAME}/{@code ${_NAME}} resolve against known substitutions. */
    private String substitute(String text, Map<String, String> substitutions) {
        if (text == null || text.indexOf('$') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$') {
                out.append(c);
                i++;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '$') {
                out.append('$');
                i += 2;
                continue;
            }
            if (i + 1 < text.length() && text.charAt(i + 1) == '{') {
                int end = text.indexOf('}', i + 2);
                if (end < 0) {
                    out.append(text, i, text.length());
                    break;
                }
                String name = text.substring(i + 2, end);
                out.append(substitutions.getOrDefault(name, text.substring(i, end + 1)));
                i = end + 1;
                continue;
            }
            int start = i + 1;
            int j = start;
            while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) {
                j++;
            }
            if (j == start) {
                out.append(c);
                i++;
                continue;
            }
            String name = text.substring(start, j);
            out.append(substitutions.getOrDefault(name, text.substring(i, j)));
            i = j;
        }
        return out.toString();
    }

    private Map<String, String> envListToMap(List<String> envList, Map<String, String> substitutions) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String entry : envList) {
            String resolved = substitute(entry, substitutions);
            int eq = resolved.indexOf('=');
            if (eq > 0) {
                env.put(resolved.substring(0, eq), resolved.substring(eq + 1));
            }
        }
        return env;
    }

    private Object mapAt(Map<?, ?> root, String key, String nestedKey) {
        Object nested = root.get(key);
        return nested instanceof Map<?, ?> m ? m.get(nestedKey) : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((k, v) -> result.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        return result;
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Yaml safeYaml() {
        LoaderOptions options = new LoaderOptions();
        options.setCodePointLimit(1_000_000);
        options.setMaxAliasesForCollections(50);
        options.setNestingDepthLimit(50);
        return new Yaml(new SafeConstructor(options));
    }
}
