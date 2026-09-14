package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParser;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Scaling;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Golden tests: render, re-parse with the real {@link ManifestParser}, validate with the real
 * {@link ManifestValidator}, assert no violations. This is the guardrail a generated manifest must
 * clear no matter what wrote it — including this writer.
 */
class ManifestYamlWriterTest {

    private final ManifestYamlWriter writer = new ManifestYamlWriter();
    private final ManifestParser parser = new ManifestParser();
    private final ManifestValidator validator =
            new ManifestValidator(new ManifestProperties(null, null, 0), new PocRuntimeProperties(null, null, null));

    @Test
    void aSingleIngressContainerRoundTripsCleanly() {
        PocManifest manifest = new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of())),
                new Resources(null, null));

        String yaml = writer.write(manifest, List.of());
        PocManifest reparsed = parser.parse(yaml);

        assertThat(validator.validate(reparsed)).isEmpty();
        assertThat(reparsed.containers()).hasSize(1);
        assertThat(reparsed.ingress().name()).isEqualTo("app");
    }

    @Test
    void aMultiContainerManifestWithEnvAndSecretsRoundTripsCleanly() {
        Map<String, String> webEnv = new LinkedHashMap<>();
        webEnv.put("BACKEND_API_URL", "${services.api.url}");
        webEnv.put("LOG_LEVEL", "info");

        Map<String, String> apiEnv = new LinkedHashMap<>();
        apiEnv.put("SERVER_PORT", "${self.port}");

        ManifestContainer web = new ManifestContainer(
                "web", ContainerRole.INGRESS, "apps/web/Dockerfile", "apps/web", 3000, webEnv, "/healthz");
        ManifestContainer api = new ManifestContainer(
                "api", ContainerRole.SIDECAR, "apps/api/Dockerfile", "apps/api", 7000, apiEnv, "/healthz", null,
                List.of(new ManifestRequirement("OPENAI_API_KEY", true)));

        PocManifest manifest = new PocManifest(
                List.of(web, api), new Resources("1", "512Mi"), new Scaling(0, 4), null);

        String yaml = writer.write(manifest, List.of("Treated 'web' as ingress: the only container with a published port."));
        PocManifest reparsed = parser.parse(yaml);

        assertThat(validator.validate(reparsed)).isEmpty();
        assertThat(reparsed.containers()).extracting(ManifestContainer::name).containsExactly("web", "api");
        assertThat(reparsed.containers().get(1).secretRequirements())
                .extracting(ManifestRequirement::name).containsExactly("OPENAI_API_KEY");
        assertThat(reparsed.resources().cpu()).isEqualTo("1");
        assertThat(reparsed.resources().memory()).isEqualTo("512Mi");
        assertThat(reparsed.scaling().min()).isZero();
        assertThat(reparsed.scaling().max()).isEqualTo(4);
    }

    /** A value containing YAML-significant characters (a raw URL) must still round-trip intact. */
    @Test
    void anEnvValueNeedingQuotesSurvivesTheRoundTrip() {
        Map<String, String> env = Map.of("ALLOWED_ORIGIN", "https://example.com:8443/path");
        PocManifest manifest = new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, env)),
                new Resources(null, null));

        String yaml = writer.write(manifest, List.of());
        PocManifest reparsed = parser.parse(yaml);

        assertThat(validator.validate(reparsed)).isEmpty();
        assertThat(reparsed.ingress().env().get("ALLOWED_ORIGIN")).isEqualTo("https://example.com:8443/path");
    }

    @Test
    void assumptionsAppearOnlyAsComments() {
        PocManifest manifest = new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of())),
                new Resources(null, null));

        String yaml = writer.write(manifest, List.of("This is a generator assumption."));

        assertThat(yaml).contains("# This is a generator assumption.");
        // A commented assumption must never itself become manifest content once re-parsed.
        assertThat(validator.validate(parser.parse(yaml))).isEmpty();
    }
}
