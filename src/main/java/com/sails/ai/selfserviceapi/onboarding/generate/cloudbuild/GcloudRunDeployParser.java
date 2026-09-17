package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.SecretHeuristics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a {@code gcloud [beta] run deploy} command's words into raw, unclassified facts —
 * {@link CloudBuildImporter} links {@link RawContainer#imageRef()}/{@link RawContainer#sourceDir()}
 * back to a build and runs {@link RawContainer#rawEnv()} through {@code EnvVarClassifier}; this
 * class only extracts what the command line literally said.
 *
 * <p>Accepts both {@code --flag=value} and {@code --flag value}. A {@code --container NAME} starts
 * a new container segment; flags before the first one apply to the (only, or default) container.
 * Service-level flags ({@code --min-instances}, {@code --allow-unauthenticated}, ...) apply
 * regardless of which segment they appear in, matching gcloud's own behavior.
 */
public class GcloudRunDeployParser {

    public record RawContainer(String name, String imageRef, String sourceDir, Integer port, String health,
                                String cpu, String memory, Map<String, String> rawEnv, List<String> secretEnvNames,
                                List<GenerationNotice> notices) {
    }

    public record RawDeploy(String serviceName, List<RawContainer> containers, Integer minInstances,
                             Integer maxInstances, List<GenerationNotice> notices) {
    }

    private static final Set<String> BOOLEAN_IGNORED = Set.of("--allow-unauthenticated", "--quiet", "--async", "--no-traffic");
    private static final Set<String> VALUE_IGNORED = Set.of("--region", "--project", "--platform", "--tag", "--revision-suffix", "--depends-on");
    private static final Set<String> BOOLEAN_UNSUPPORTED = Set.of("--no-allow-unauthenticated", "--no-cpu-throttling", "--cpu-boost", "--use-http2");
    private static final Set<String> VALUE_UNSUPPORTED = Set.of(
            "--timeout", "--concurrency", "--execution-environment", "--service-account", "--ingress",
            "--vpc-connector", "--vpc-egress", "--network", "--subnet", "--add-cloudsql-instances",
            "--set-cloudsql-instances", "--command", "--args", "--add-volume", "--add-volume-mount",
            "--session-affinity", "--labels");

    private static final Pattern HTTP_GET_PATH = Pattern.compile("httpGet\\.path=([^,]+)");

    public Optional<RawDeploy> parse(List<String> words) {
        int i = skipToDeployVerb(words);
        if (i < 0) {
            return Optional.empty();
        }

        String serviceName = null;
        List<GenerationNotice> serviceNotices = new ArrayList<>();
        Integer minInstances = null;
        Integer maxInstances = null;
        List<ContainerBuilder> segments = new ArrayList<>();
        segments.add(new ContainerBuilder(null));

        for (; i < words.size(); i++) {
            String word = words.get(i);
            if (!word.startsWith("-")) {
                if (serviceName == null) {
                    serviceName = sanitizeServiceName(word);
                }
                continue;
            }
            String[] flag = splitFlagEquals(word);
            String name = flag[0];
            ContainerBuilder current = segments.get(segments.size() - 1);

            switch (name) {
                case "--container" -> segments.add(new ContainerBuilder(valueOf(flag, words, i, name).orElse(null)));
                case "--image" -> current.imageRef = valueOf(flag, words, i, name).orElse(current.imageRef);
                case "--source" -> current.sourceDir = valueOf(flag, words, i, name).orElse(current.sourceDir);
                case "--port" -> current.port = valueOf(flag, words, i, name).map(this::parseIntOrNull).orElse(current.port);
                case "--cpu" -> current.cpu = valueOf(flag, words, i, name).orElse(current.cpu);
                case "--memory" -> current.memory = valueOf(flag, words, i, name).orElse(current.memory);
                case "--min-instances" -> minInstances = valueOf(flag, words, i, name).map(this::parseIntOrNull).orElse(minInstances);
                case "--max-instances" -> maxInstances = valueOf(flag, words, i, name).map(this::parseIntOrNull).orElse(maxInstances);
                case "--set-env-vars", "--update-env-vars" ->
                        valueOf(flag, words, i, name).ifPresent(v -> current.rawEnv.putAll(parseDelimited(v)));
                case "--env-vars-file" -> current.notices.add(GenerationNotice.info(GenerationNoticeCode.ENV_FILE_NOT_IN_REPO,
                        "This deploy reads env vars from " + valueOf(flag, words, i, name).orElse("a file")
                                + " — the importer only reads it if it is committed to the repo at that path."));
                case "--set-secrets", "--update-secrets" ->
                        valueOf(flag, words, i, name).ifPresent(v -> current.secretEnvNames.addAll(parseSecretNames(v, current.notices)));
                case "--startup-probe" -> valueOf(flag, words, i, name).ifPresent(v -> current.startupHealth = extractHttpGetPath(v));
                case "--liveness-probe" -> valueOf(flag, words, i, name).ifPresent(v -> current.livenessHealth = extractHttpGetPath(v));
                default -> {
                    if (word.startsWith("--clear-") || word.startsWith("--remove-")) {
                        // platform-managed or derived — nothing to import
                    } else if (BOOLEAN_IGNORED.contains(name)) {
                        // platform-managed or derived
                    } else if (VALUE_IGNORED.contains(name)) {
                        valueOf(flag, words, i, name);
                    } else if (BOOLEAN_UNSUPPORTED.contains(name) || name.startsWith("--gpu")) {
                        serviceNotices.add(unsupportedNotice(name, null));
                    } else if (VALUE_UNSUPPORTED.contains(name)) {
                        String value = valueOf(flag, words, i, name).orElse(null);
                        serviceNotices.add(unsupportedNotice(name, value));
                    }
                    // an entirely unrecognized flag is silently skipped — safer than guessing
                    // whether it consumes the next word as a value.
                }
            }
            // valueOf may have consumed the next word; re-derive i from how many words were used.
            i = advancePastValue(flag, words, i, name);
        }

        // The implicit "default" segment created up front is only real if some flag actually landed
        // on it before the first --container appeared; once --container is used at all, an unused
        // leading segment is bookkeeping, not a container.
        if (segments.size() > 1 && segments.get(0).isEmpty()) {
            segments.remove(0);
        }
        List<RawContainer> containers = new ArrayList<>();
        for (ContainerBuilder b : segments) {
            containers.add(b.build());
        }
        List<GenerationNotice> notices = new ArrayList<>(serviceNotices);
        return Optional.of(new RawDeploy(serviceName, containers, minInstances, maxInstances, notices));
    }

    private int skipToDeployVerb(List<String> words) {
        int i = 0;
        if (i < words.size() && words.get(i).equals("gcloud")) {
            i++;
        } else {
            return -1;
        }
        if (i < words.size() && words.get(i).equals("beta")) {
            i++;
        }
        if (i + 1 < words.size() && words.get(i).equals("run") && words.get(i + 1).equals("deploy")) {
            return i + 2;
        }
        return -1;
    }

    private GenerationNotice unsupportedNotice(String flag, String value) {
        boolean secretShaped = value != null && SecretHeuristics.valueLooksSecret(value);
        String shown = value == null ? "" : (secretShaped ? " (value withheld — looks like a secret)" : (" " + value));
        return GenerationNotice.info(GenerationNoticeCode.UNSUPPORTED_SETTING,
                "'" + flag + shown + "' from this deploy is not imported — review whether it matters for this POC.");
    }

    private String sanitizeServiceName(String candidate) {
        String lower = candidate.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        lower = lower.replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        if (lower.isEmpty()) {
            return "app";
        }
        return lower.length() > 40 ? lower.substring(0, 40).replaceAll("-+$", "") : lower;
    }

    private String extractHttpGetPath(String probeSpec) {
        Matcher m = HTTP_GET_PATH.matcher(probeSpec);
        return m.find() ? m.group(1) : null;
    }

    /** {@code ^D^K1=V1D K2=V2} alternate-delimiter form, or the default comma-separated {@code K1=V1,K2=V2}. */
    private Map<String, String> parseDelimited(String raw) {
        String delimiter = ",";
        String body = raw;
        if (raw.startsWith("^")) {
            int end = raw.indexOf('^', 1);
            if (end > 0) {
                delimiter = raw.substring(1, end);
                body = raw.substring(end + 1);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : body.split(Pattern.quote(delimiter), -1)) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
        return result;
    }

    private List<String> parseSecretNames(String raw, List<GenerationNotice> notices) {
        if (raw.contains("/") && raw.contains("=") && raw.trim().startsWith("/")) {
            notices.add(GenerationNotice.info(GenerationNoticeCode.FILE_SECRET_UNSUPPORTED,
                    "This deploy mounts a secret as a file (" + raw + "); only environment-variable secrets are imported."));
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, String> entry : parseDelimited(raw).entrySet()) {
            if (entry.getKey().trim().startsWith("/")) {
                notices.add(GenerationNotice.info(GenerationNoticeCode.FILE_SECRET_UNSUPPORTED,
                        "This deploy mounts a secret as a file at " + entry.getKey() + "; only environment-variable "
                                + "secrets are imported."));
            } else {
                names.add(entry.getKey());
            }
        }
        return names;
    }

    private Integer parseIntOrNull(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String[] splitFlagEquals(String word) {
        int eq = word.indexOf('=');
        return eq < 0 ? new String[]{word, null} : new String[]{word.substring(0, eq), word.substring(eq + 1)};
    }

    /** The value for a {@code --flag=value} or {@code --flag value} pair, without mutating the scan position. */
    private Optional<String> valueOf(String[] flag, List<String> words, int index, String flagName) {
        if (flag[1] != null) {
            return Optional.of(flag[1]);
        }
        int next = index + 1;
        if (next < words.size() && !words.get(next).startsWith("-")) {
            return Optional.of(words.get(next));
        }
        return Optional.empty();
    }

    /** Mirrors {@link #valueOf}'s decision about whether a following bare word was consumed as this flag's value. */
    private int advancePastValue(String[] flag, List<String> words, int index, String flagName) {
        if (flag[1] != null) {
            return index;
        }
        if (BOOLEAN_IGNORED.contains(flagName) || BOOLEAN_UNSUPPORTED.contains(flagName)
                || flagName.startsWith("--clear-") || flagName.startsWith("--remove-")) {
            return index;
        }
        int next = index + 1;
        if (next < words.size() && !words.get(next).startsWith("-")) {
            return next;
        }
        return index;
    }

    private static final class ContainerBuilder {
        String name;
        String imageRef;
        String sourceDir;
        Integer port;
        String cpu;
        String memory;
        String startupHealth;
        String livenessHealth;
        final Map<String, String> rawEnv = new LinkedHashMap<>();
        final List<String> secretEnvNames = new ArrayList<>();
        final List<GenerationNotice> notices = new ArrayList<>();

        ContainerBuilder(String name) {
            this.name = name;
        }

        boolean isEmpty() {
            return name == null && imageRef == null && sourceDir == null && port == null && cpu == null
                    && memory == null && startupHealth == null && livenessHealth == null
                    && rawEnv.isEmpty() && secretEnvNames.isEmpty();
        }

        RawContainer build() {
            // The startup probe wins over the liveness probe when both are set.
            String health = startupHealth != null ? startupHealth : livenessHealth;
            return new RawContainer(name, imageRef, sourceDir, port, health, cpu, memory, rawEnv, secretEnvNames, notices);
        }
    }
}
