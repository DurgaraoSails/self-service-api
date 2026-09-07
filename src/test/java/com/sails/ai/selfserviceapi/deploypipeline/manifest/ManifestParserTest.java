package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser();

    @Test
    void parsesASingleIngressContainerWithDefaults() {
        PocManifest manifest = parser.parse("""
                containers:
                  - name: app
                    role: ingress
                """);

        assertThat(manifest.containers()).hasSize(1);
        ManifestContainer container = manifest.containers().get(0);
        assertThat(container.name()).isEqualTo("app");
        assertThat(container.role()).isEqualTo(ContainerRole.INGRESS);
        assertThat(container.dockerfile()).isEqualTo("Dockerfile");
        assertThat(container.context()).isEqualTo(".");
        assertThat(container.port()).isNull();
        assertThat(container.env()).isEmpty();
    }

    @Test
    void parsesAnIngressAndASidecarWithExplicitFieldsAndResources() {
        PocManifest manifest = parser.parse("""
                containers:
                  - name: api
                    role: ingress
                    env:
                      LOG_LEVEL: info
                  - name: worker
                    role: sidecar
                    dockerfile: worker/Dockerfile
                    context: worker
                    port: 9000
                    env:
                      QUEUE_NAME: jobs
                resources:
                  cpu: "1"
                  memory: 512Mi
                """);

        assertThat(manifest.containers()).hasSize(2);
        ManifestContainer worker = manifest.containers().get(1);
        assertThat(worker.name()).isEqualTo("worker");
        assertThat(worker.role()).isEqualTo(ContainerRole.SIDECAR);
        // Both independently relative to the repo root — see ManifestContainer's javadoc for why
        // dockerfile is NOT resolved relative to context.
        assertThat(worker.dockerfile()).isEqualTo("worker/Dockerfile");
        assertThat(worker.context()).isEqualTo("worker");
        assertThat(worker.port()).isEqualTo(9000);
        assertThat(worker.env()).containsEntry("QUEUE_NAME", "jobs");

        assertThat(manifest.containers().get(0).env()).containsEntry("LOG_LEVEL", "info");
        assertThat(manifest.resources().cpu()).isEqualTo("1");
        assertThat(manifest.resources().memory()).isEqualTo("512Mi");
    }

    @Test
    void ingressHelperFindsTheIngressContainerRegardlessOfPosition() {
        PocManifest manifest = parser.parse("""
                containers:
                  - name: worker
                    role: sidecar
                    port: 9000
                  - name: api
                    role: ingress
                """);

        assertThat(manifest.ingress().name()).isEqualTo("api");
    }

    @Test
    void throwsOnMalformedYaml() {
        assertThatThrownBy(() -> parser.parse("containers: [this is not: valid: yaml"))
                .isInstanceOf(ManifestParseException.class);
    }

    @Test
    void throwsWhenContainersIsMissing() {
        assertThatThrownBy(() -> parser.parse("resources:\n  cpu: \"1\"\n"))
                .isInstanceOf(ManifestParseException.class)
                .hasMessageContaining("containers");
    }

    @Test
    void throwsWhenContainersIsEmpty() {
        assertThatThrownBy(() -> parser.parse("containers: []\n"))
                .isInstanceOf(ManifestParseException.class);
    }

    @Test
    void throwsOnAnUnrecognizedRole() {
        assertThatThrownBy(() -> parser.parse("""
                containers:
                  - name: app
                    role: sidekick
                """))
                .isInstanceOf(ManifestParseException.class)
                .hasMessageContaining("sidekick");
    }

    @Test
    void throwsWhenAContainerHasNoName() {
        assertThatThrownBy(() -> parser.parse("""
                containers:
                  - role: ingress
                """))
                .isInstanceOf(ManifestParseException.class);
    }

    @Test
    void aContainerWithNoEnvGetsAnEmptyMapNotNull() {
        PocManifest manifest = parser.parse("""
                containers:
                  - name: app
                    role: ingress
                """);

        assertThat(manifest.containers().get(0).env()).isEqualTo(Map.of());
    }
}
