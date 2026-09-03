package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * Parses poc.yaml into a {@link PocManifest} and validates it. SnakeYAML rather than
 * jackson-dataformat-yaml — it's already on the classpath transitively via spring-boot-starter
 * (used for application.yaml), so this avoids a new dependency for what's otherwise a
 * self-contained mapping into a handful of records.
 */
@Component
public class ManifestParser {

    private final ManifestValidator validator;

    public ManifestParser(ManifestValidator validator) {
        this.validator = validator;
    }

    @SuppressWarnings("unchecked")
    public PocManifest parse(String yaml) {
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map)) {
            throw new InvalidPocManifestException(List.of("poc.yaml must be a YAML mapping at the top level"));
        }
        Map<String, Object> root = (Map<String, Object>) loaded;

        List<ManifestContainer> containers = new ArrayList<>();
        if (root.get("containers") instanceof List<?> rawContainers) {
            for (Object item : rawContainers) {
                if (item instanceof Map) {
                    containers.add(parseContainer((Map<String, Object>) item));
                }
            }
        }

        PocManifest manifest = new PocManifest(
                str(root, "apiVersion"),
                str(root, "name"),
                str(root, "description"),
                str(root, "team"),
                containers,
                parseResources((Map<String, Object>) root.get("resources")),
                parseScaling((Map<String, Object>) root.get("scaling")),
                parsePlatform((Map<String, Object>) root.get("platform")));

        validator.validate(manifest);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private ManifestContainer parseContainer(Map<String, Object> m) {
        ContainerRole role = switch (String.valueOf(str(m, "role"))) {
            case "ingress" -> ContainerRole.INGRESS;
            case "sidecar" -> ContainerRole.SIDECAR;
            default -> null; // caught by ManifestValidator, which reports it with the container's name
        };

        Map<String, String> env = new LinkedHashMap<>();
        if (m.get("env") instanceof Map<?, ?> rawEnv) {
            rawEnv.forEach((k, v) -> env.put(String.valueOf(k), String.valueOf(v)));
        }

        return new ManifestContainer(
                str(m, "name"),
                role,
                orDefault(str(m, "dockerfile"), "Dockerfile"),
                orDefault(str(m, "context"), "."),
                m.get("port") instanceof Number port ? port.intValue() : null,
                orDefault(str(m, "health"), "/healthz"),
                str(m, "repo"),
                env);
    }

    private Resources parseResources(Map<String, Object> m) {
        if (m == null) {
            return Resources.defaults();
        }
        return new Resources(
                orDefault(str(m, "cpu"), Resources.defaults().cpu()),
                orDefault(str(m, "memory"), Resources.defaults().memory()));
    }

    private Scaling parseScaling(Map<String, Object> m) {
        if (m == null) {
            return Scaling.defaults();
        }
        int min = m.get("min") instanceof Number n ? n.intValue() : Scaling.defaults().min();
        int max = m.get("max") instanceof Number n ? n.intValue() : Scaling.defaults().max();
        return new Scaling(min, max);
    }

    @SuppressWarnings("unchecked")
    private Platform parsePlatform(Map<String, Object> m) {
        if (m == null) {
            return Platform.none();
        }
        return new Platform(
                boolAt((Map<String, Object>) m.get("database")),
                boolAt((Map<String, Object>) m.get("files")));
    }

    private boolean boolAt(Map<String, Object> sub) {
        return sub != null && Boolean.TRUE.equals(sub.get("enabled"));
    }

    private String str(Map<String, Object> m, String key) {
        Object value = m.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
