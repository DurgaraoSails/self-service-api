package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The template a POC team copies has to be a manifest this platform actually accepts.
 *
 * <p>Before it existed the schema was restated in prose in five places and agreed with the
 * validator in none of them by construction. A shipped example that is only checked by eye becomes
 * the sixth. Parsing and validating the real file is what makes it impossible for the two to drift:
 * change a rule without changing the template and this fails.
 */
class ShippedTemplateTest {

    private static final Path TEMPLATE = Path.of("docs", "poc.yaml.template");

    @Test
    void theShippedTemplateParsesAndValidates() throws IOException {
        assertThat(TEMPLATE).exists();

        PocManifest manifest = new ManifestParser().parse(Files.readString(TEMPLATE));

        List<String> violations = new ManifestValidator(new ManifestProperties(null, null, 8),
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com"))
                .validate(manifest);

        assertThat(violations).isEmpty();
    }

    /**
     * The template exists to demonstrate the things a manifest is for. If it stops showing a sidecar
     * or a placeholder, the feature it documents has quietly lost its only worked example.
     */
    @Test
    void theShippedTemplateStillDemonstratesWhatItIsFor() throws IOException {
        PocManifest manifest = new ManifestParser().parse(Files.readString(TEMPLATE));

        assertThat(manifest.containers()).hasSizeGreaterThan(1);
        assertThat(manifest.containers()).anySatisfy(container ->
                assertThat(container.role()).isEqualTo(ContainerRole.SIDECAR));
        assertThat(manifest.containers()).allSatisfy(container ->
                assertThat(container.health()).as("every container should model a health path").isNotBlank());
        assertThat(manifest.containers()).anySatisfy(container ->
                assertThat(container.env().values()).anySatisfy(value ->
                        assertThat(value).contains("${services.")));
        assertThat(manifest.containers()).anySatisfy(container ->
                assertThat(container.env().values()).anySatisfy(value ->
                        assertThat(value).contains("${self.port}")));
    }

    /** A schema shipped beside the template and never referenced from it would help nobody. */
    @Test
    void theTemplatePointsAtTheSchemaThatShipsWithIt() throws IOException {
        assertThat(Path.of("docs", "poc.schema.json")).exists();
        assertThat(Files.readString(TEMPLATE)).contains("$schema=./poc.schema.json");
    }
}
