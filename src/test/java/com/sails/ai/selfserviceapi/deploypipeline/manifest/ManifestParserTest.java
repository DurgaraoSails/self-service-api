package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ManifestParserTest {

    private final ManifestParser parser = new ManifestParser(new ManifestValidator());

    @Test
    void parsesAFullMultiContainerManifest() {
        String yaml = """
                apiVersion: sails.poc/v1
                name: Research Assistant Suite
                team: ai-platform
                containers:
                  - name: web
                    role: ingress
                    dockerfile: apps/web/Dockerfile
                    context: apps/web
                  - name: api
                    role: sidecar
                    port: 8081
                    health: /live
                    env:
                      LOG_LEVEL: debug
                resources: { cpu: "4", memory: "4Gi" }
                scaling: { min: 1, max: 5 }
                platform:
                  database: { enabled: true, extensions: [vector] }
                  files: { enabled: true }
                """;

        PocManifest manifest = parser.parse(yaml);

        assertThat(manifest.name()).isEqualTo("Research Assistant Suite");
        assertThat(manifest.team()).isEqualTo("ai-platform");
        assertThat(manifest.containers()).hasSize(2);

        ManifestContainer web = manifest.ingress();
        assertThat(web.name()).isEqualTo("web");
        assertThat(web.dockerfile()).isEqualTo("apps/web/Dockerfile");
        assertThat(web.context()).isEqualTo("apps/web");
        assertThat(web.health()).isEqualTo("/healthz"); // defaulted — not set in the yaml

        ManifestContainer api = manifest.sidecars().get(0);
        assertThat(api.port()).isEqualTo(8081);
        assertThat(api.health()).isEqualTo("/live");
        assertThat(api.env()).containsEntry("LOG_LEVEL", "debug");

        assertThat(manifest.resources()).isEqualTo(new Resources("4", "4Gi"));
        assertThat(manifest.scaling()).isEqualTo(new Scaling(1, 5));
        assertThat(manifest.platform()).isEqualTo(new Platform(true, true));
    }

    @Test
    void missingOptionalSectionsFallBackToDefaults() {
        String yaml = """
                apiVersion: sails.poc/v1
                containers:
                  - name: app
                    role: ingress
                """;

        PocManifest manifest = parser.parse(yaml);

        assertThat(manifest.resources()).isEqualTo(Resources.defaults());
        assertThat(manifest.scaling()).isEqualTo(Scaling.defaults());
        assertThat(manifest.platform()).isEqualTo(Platform.none());
        assertThat(manifest.ingress().dockerfile()).isEqualTo("Dockerfile");
        assertThat(manifest.ingress().context()).isEqualTo(".");
    }

    @Test
    void invalidManifestFailsThroughTheValidatorDuringParse() {
        String yaml = """
                apiVersion: sails.poc/v1
                containers:
                  - name: web
                    role: ingress
                  - name: api
                    role: ingress
                """;

        assertThatThrownBy(() -> parser.parse(yaml))
                .isInstanceOf(InvalidPocManifestException.class)
                .hasMessageContaining("found 2");
    }

    @Test
    void rejectsNonMappingTopLevelYaml() {
        assertThatThrownBy(() -> parser.parse("- just\n- a\n- list\n"))
                .isInstanceOf(InvalidPocManifestException.class)
                .hasMessageContaining("must be a YAML mapping");
    }
}
