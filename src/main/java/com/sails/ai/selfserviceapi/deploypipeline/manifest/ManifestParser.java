package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final String DEFAULT_DOCKERFILE = "Dockerfile";
    private static final String DEFAULT_CONTEXT = ".";

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
        return new PocManifest(containers, resources);
    }

    private ManifestContainer parseContainer(Map<String, Object> map) {
        String name = requireString(map, "name");
        ContainerRole role = parseRole(requireString(map, "role"), name);
        String dockerfile = optionalString(map, "dockerfile", DEFAULT_DOCKERFILE);
        String context = optionalString(map, "context", DEFAULT_CONTEXT);
        Integer port = optionalInt(map, "port");
        Map<String, String> env = parseEnv(map.get("env"), name);
        return new ManifestContainer(name, role, dockerfile, context, port, env);
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
