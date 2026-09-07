package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Turns a poc.yaml's raw text into a {@link PocManifest}. Mapped by hand, field by field, rather
 * than through a generic YAML-to-record deserializer — matches this codebase's existing preference
 * (see {@code PocDeploymentResponseMapper}) for explicit mapping over a mapping framework, and
 * keeps every default (e.g. "Dockerfile", ".") visible in one place instead of hidden in
 * annotations.
 *
 * <p>Parsing alone never rejects a manifest for being semantically wrong (missing ingress, bad
 * name, reserved env var, too many containers) — that is {@link ManifestValidator}'s job. This
 * class only rejects YAML that isn't shaped like a manifest at all.
 */
@Component
public class ManifestParser {

    private static final Logger log = LoggerFactory.getLogger(ManifestParser.class);

    private static final String DEFAULT_DOCKERFILE = "Dockerfile";
    private static final String DEFAULT_CONTEXT = ".";

    /**
     * Everything this parser reads, plus the descriptive keys it deliberately ignores
     * ({@code apiVersion}, {@code name}, {@code description}, {@code team}) — those document the
     * POC for a human and drive nothing here, so warning about them would be noise on every
     * manifest written against the published schema.
     */
    private static final Set<String> KNOWN_TOP_LEVEL_KEYS =
            Set.of("apiVersion", "name", "description", "team", "containers", "resources", "scaling", "platform");

    private static final Set<String> KNOWN_CONTAINER_KEYS =
            Set.of("name", "role", "dockerfile", "context", "port", "env", "health", "repo");

    @SuppressWarnings("unchecked")
    public PocManifest parse(String yaml) {
        Map<String, Object> root;
        try {
            root = new Yaml().load(yaml);
        } catch (YAMLException e) {
            throw new ManifestParseException(e.getMessage());
        }
        if (root == null) {
            throw new ManifestParseException("the file is empty");
        }

        Object rawContainers = root.get("containers");
        if (!(rawContainers instanceof List<?> containerList) || containerList.isEmpty()) {
            throw new ManifestParseException("'containers' must be a non-empty list");
        }

        List<ManifestContainer> containers = new ArrayList<>();
        for (Object entry : containerList) {
            if (!(entry instanceof Map<?, ?> map)) {
                throw new ManifestParseException("each entry under 'containers' must be a mapping");
            }
            containers.add(parseContainer((Map<String, Object>) map));
        }

        Resources resources = parseResources((Map<String, Object>) root.get("resources"));
        Scaling scaling = parseScaling((Map<String, Object>) root.get("scaling"));
        PlatformConfig platform = parsePlatform((Map<String, Object>) root.get("platform"));

        warnAboutUnsupportedKeys(root, containerList);
        return new PocManifest(containers, resources, scaling, platform);
    }

    private ManifestContainer parseContainer(Map<String, Object> map) {
        String name = requireString(map, "name");
        ContainerRole role = parseRole(requireString(map, "role"), name);
        String dockerfile = optionalString(map, "dockerfile", DEFAULT_DOCKERFILE);
        String context = optionalString(map, "context", DEFAULT_CONTEXT);
        Integer port = optionalInt(map, "port");
        Map<String, String> env = parseEnv(map.get("env"), name);
        String health = optionalString(map, "health", null);
        String repo = optionalString(map, "repo", null);
        return new ManifestContainer(name, role, dockerfile, context, port, env, health, repo);
    }

    /**
     * Warned about, never rejected. A POC repo written against the fuller published
     * {@code poc-platform-sdk} schema must keep deploying even when this pipeline understands only
     * part of it — but an author who writes a key that does nothing, or misspells one that would
     * have, otherwise has no way at all to discover it. Logged rather than returned because every
     * caller of this parser wants the same thing done with it, on both the build and the
     * redeploy path.
     */
    private void warnAboutUnsupportedKeys(Map<String, Object> root, List<?> containerList) {
        Set<String> unsupported = new LinkedHashSet<>(root.keySet());
        unsupported.removeAll(KNOWN_TOP_LEVEL_KEYS);

        for (Object entry : containerList) {
            if (entry instanceof Map<?, ?> map) {
                map.keySet().stream()
                        .map(String::valueOf)
                        .filter(key -> !KNOWN_CONTAINER_KEYS.contains(key))
                        .forEach(key -> unsupported.add("containers[]." + key));
            }
        }

        if (!unsupported.isEmpty()) {
            log.warn("unsupported manifest keys, ignored: {}", String.join(", ", unsupported));
        }
    }

    private ContainerRole parseRole(String raw, String containerName) {
        try {
            return ContainerRole.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ManifestParseException(
                    "container '" + containerName + "' has an unrecognized role '" + raw + "' (expected ingress or sidecar)");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseEnv(Object raw, String containerName) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new ManifestParseException("container '" + containerName + "'s 'env' must be a mapping");
        }
        Map<String, String> env = new LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((key, value) -> env.put(key, value == null ? null : value.toString()));
        return env;
    }

    private Resources parseResources(Map<String, Object> map) {
        if (map == null) {
            return new Resources(null, null);
        }
        return new Resources(optionalString(map, "cpu", null), optionalString(map, "memory", null));
    }

    private Scaling parseScaling(Map<String, Object> map) {
        if (map == null) {
            return Scaling.none();
        }
        return new Scaling(optionalInt(map, "min"), optionalInt(map, "max"));
    }

    @SuppressWarnings("unchecked")
    private PlatformConfig parsePlatform(Map<String, Object> map) {
        if (map == null) {
            return PlatformConfig.none();
        }
        boolean database = optionalBoolean((Map<String, Object>) map.get("database"));
        boolean files = optionalBoolean((Map<String, Object>) map.get("files"));
        return new PlatformConfig(database, files);
    }

    private boolean optionalBoolean(Map<String, Object> map) {
        if (map == null) {
            return false;
        }
        Object value = map.get("enabled");
        return value instanceof Boolean b && b;
    }

    private String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new ManifestParseException("every container must declare a non-empty '" + key + "'");
        }
        return s;
    }

    private String optionalString(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private Integer optionalInt(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new ManifestParseException("'" + key + "' must be a number");
    }
}
