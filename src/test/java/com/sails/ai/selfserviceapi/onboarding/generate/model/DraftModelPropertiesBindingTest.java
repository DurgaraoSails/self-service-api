package com.sails.ai.selfserviceapi.onboarding.generate.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies that {@code application.yaml}'s {@code poc-generator:} block actually binds to
 * {@link DraftModelProperties} the way its {@code ${ENV_VAR:default}} placeholders promise —
 * relaxed binding (kebab-case YAML to camelCase record components, nested {@code ollama:}/
 * {@code vertex:} blocks to the nested records) is trusted rather than re-derived elsewhere in
 * this codebase, so this is the one place that actually loads the real file through Spring's own
 * YAML/binder machinery instead of only constructing the record directly.
 *
 * <p>Loads the YAML and binds it directly with {@link Binder} rather than booting a
 * {@code SpringApplication}/{@code ApplicationContext} — this app needs a database and several
 * other beans to start, none of which this is about.
 */
class DraftModelPropertiesBindingTest {

    private static Binder binderWithEnv(Map<String, String> env) {
        List<PropertySource<?>> loaded;
        try {
            loaded = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        MutablePropertySources sources = new MutablePropertySources();
        // ${ENV_VAR} placeholders in application.yaml resolve against whichever property source
        // carries that literal name — the same role a real environment variable plays at runtime.
        sources.addFirst(new MapPropertySource("test-env", Map.copyOf(env)));
        loaded.forEach(sources::addLast);
        return new Binder(org.springframework.boot.context.properties.source.ConfigurationPropertySources.from(sources),
                new PropertySourcesPlaceholdersResolver(sources));
    }

    @Test
    void bindsTheDefaultsFromApplicationYamlWithNoOverrides() {
        DraftModelProperties properties = binderWithEnv(Map.of())
                .bind("poc-generator", Bindable.of(DraftModelProperties.class))
                .get();

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.provider()).isEqualTo("ollama");
        assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(120));

        assertThat(properties.ollama().baseUrl()).isEqualTo("http://localhost:11434");
        assertThat(properties.ollama().model()).isEqualTo("qwen2.5-coder:32b");
        assertThat(properties.ollama().numCtx()).isEqualTo(32_000);
        assertThat(properties.ollama().temperature()).isEqualTo(0);

        assertThat(properties.vertex().project()).isNullOrEmpty();
        assertThat(properties.vertex().location()).isEqualTo("us-central1");
        assertThat(properties.vertex().model()).isEqualTo("gemini-3.1-flash-lite");

        assertThat(properties.maxDockerfileModelCalls()).isEqualTo(2);
    }

    @Test
    void maxDockerfileModelCallsEnvVarOverridesItsOwnValue() {
        DraftModelProperties properties = binderWithEnv(Map.of(
                "POC_GENERATOR_MAX_DOCKERFILE_MODEL_CALLS", "5"
        )).bind("poc-generator", Bindable.of(DraftModelProperties.class)).get();

        assertThat(properties.maxDockerfileModelCalls()).isEqualTo(5);
    }

    @Test
    void everyEnvVarPlaceholderOverridesItsOwnValue() {
        DraftModelProperties properties = binderWithEnv(Map.of(
                "POC_GENERATOR_ENABLED", "true",
                "POC_GENERATOR_PROVIDER", "vertex",
                "POC_GENERATOR_VERTEX_PROJECT", "acme-poc",
                "POC_GENERATOR_VERTEX_LOCATION", "europe-west1",
                "POC_GENERATOR_VERTEX_MODEL", "gemini-2.5-flash"
        )).bind("poc-generator", Bindable.of(DraftModelProperties.class)).get();

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.provider()).isEqualTo("vertex");
        assertThat(properties.vertex().project()).isEqualTo("acme-poc");
        assertThat(properties.vertex().location()).isEqualTo("europe-west1");
        assertThat(properties.vertex().model()).isEqualTo("gemini-2.5-flash");
    }

    @Test
    void everyOllamaEnvVarPlaceholderOverridesItsOwnValue() {
        DraftModelProperties properties = binderWithEnv(Map.of(
                "POC_GENERATOR_OLLAMA_BASE_URL", "http://ollama.internal:11434",
                "POC_GENERATOR_OLLAMA_MODEL", "qwen2.5-coder:14b",
                "POC_GENERATOR_OLLAMA_NUM_CTX", "16000",
                "POC_GENERATOR_OLLAMA_TEMPERATURE", "0.2"
        )).bind("poc-generator", Bindable.of(DraftModelProperties.class)).get();

        assertThat(properties.ollama().baseUrl()).isEqualTo("http://ollama.internal:11434");
        assertThat(properties.ollama().model()).isEqualTo("qwen2.5-coder:14b");
        assertThat(properties.ollama().numCtx()).isEqualTo(16_000);
        assertThat(properties.ollama().temperature()).isEqualTo(0.2);
    }
}
