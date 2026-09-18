package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

/**
 * Renders the vetted, per-stack Dockerfile/.dockerignore/nginx-config templates —
 * {@code src/main/resources/templates/dockerfiles/<stack>.{Dockerfile,dockerignore}.tmpl} and the
 * shared {@code nginx-default.conf.template.tmpl}. Every substitution is {@code {{param}}}; a
 * shell-style {@code ${PORT}} in a template (nginx's own envsubst config, or an ENV line) is left
 * completely alone by this class — the two syntaxes never collide.
 *
 * <p>Fails loudly on an unknown or unfilled placeholder rather than silently emitting literal
 * {@code {{...}}} text into a Dockerfile a team would otherwise commit unreviewed-looking but
 * broken — {@code DockerfileTemplatesTest} asserts every stack renders with none left over.
 */
@Component
public class DockerfileTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");
    private static final String NGINX_CONFIG_RESOURCE = "templates/dockerfiles/nginx-default.conf.template.tmpl";
    private static final String NGINX_CONFIG_WITH_BACKEND_RESOURCE =
            "templates/dockerfiles/nginx-default-with-backend.conf.template.tmpl";

    public String renderDockerfile(StackKind kind, Map<String, String> params) {
        return render(resourcePath(kind, "Dockerfile.tmpl"), withStackName(kind, params));
    }

    public String renderDockerignore(StackKind kind, Map<String, String> params) {
        return render(resourcePath(kind, "dockerignore.tmpl"), withStackName(kind, params));
    }

    /**
     * Shared by every SPA-shaped stack (Angular/Vite/CRA/static) — no {{param}}s, {@code ${PORT}}
     * and {@code ${BACKEND_URL}} are nginx's own {@code envsubst} at container start, not this
     * class's placeholder syntax.
     *
     * @param withBackendProxy true to also proxy {@code /api/} to whatever {@code BACKEND_URL} is
     *                         bound to — the caller is responsible for actually binding it in the
     *                         same container's {@code env:} (see {@code DockerfilePlanner}); this
     *                         method only picks which config text to render.
     */
    public String renderNginxConfig(boolean withBackendProxy) {
        return render(withBackendProxy ? NGINX_CONFIG_WITH_BACKEND_RESOURCE : NGINX_CONFIG_RESOURCE, Map.of());
    }

    public boolean hasTemplate(StackKind kind) {
        return new ClassPathResource(resourcePath(kind, "Dockerfile.tmpl")).exists();
    }

    private Map<String, String> withStackName(StackKind kind, Map<String, String> params) {
        Map<String, String> merged = new LinkedHashMap<>(params);
        merged.putIfAbsent("stack", displayName(kind));
        return merged;
    }

    private String displayName(StackKind kind) {
        return switch (kind) {
            case ANGULAR_SPA -> "Angular";
            case VITE_SPA -> "Vite";
            case CRA_SPA -> "Create React App";
            case NEXTJS -> "Next.js";
            case NODE_SERVER -> "Node.js";
            case STATIC_SITE -> "static site";
            case JAVA_MAVEN -> "Java (Maven)";
            case JAVA_GRADLE -> "Java (Gradle)";
            case PYTHON_FASTAPI -> "FastAPI";
            case PYTHON_FLASK -> "Flask";
            case PYTHON_DJANGO -> "Django";
            case PYTHON_STREAMLIT -> "Streamlit";
            case PYTHON_GRADIO -> "Gradio";
            case GO -> "Go";
            case DOTNET -> ".NET";
            case UNKNOWN -> "unknown";
        };
    }

    private String resourcePath(StackKind kind, String suffix) {
        String stackFileName = kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return "templates/dockerfiles/" + stackFileName + "." + suffix;
    }

    private String render(String resourcePath, Map<String, String> params) {
        String template = loadResource(resourcePath);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = params.get(key);
            if (value == null) {
                throw new IllegalStateException(
                        "Unknown or unfilled placeholder '{{" + key + "}}' in " + resourcePath);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private String loadResource(String resourcePath) {
        try {
            byte[] bytes = FileCopyUtils.copyToByteArray(new ClassPathResource(resourcePath).getInputStream());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing Dockerfile template resource: " + resourcePath, e);
        }
    }
}
