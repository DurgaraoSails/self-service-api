package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestValidatorTest {

    private final ManifestValidator validator = new ManifestValidator(new ManifestProperties(null, null, 0));

    private static ManifestContainer ingress(String name) {
        return new ManifestContainer(name, ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
    }

    private static ManifestContainer sidecar(String name, int port) {
        return new ManifestContainer(name, ContainerRole.SIDECAR, "Dockerfile", ".", port, Map.of());
    }

    @Test
    void aValidSingleIngressManifestHasNoViolations() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void aValidIngressPlusSidecarManifestHasNoViolations() {
        PocManifest manifest = new PocManifest(List.of(ingress("api"), sidecar("worker", 9000)), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void rejectsZeroIngressContainers() {
        PocManifest manifest = new PocManifest(List.of(sidecar("worker", 9000)), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("ingress"));
    }

    @Test
    void rejectsMultipleIngressContainers() {
        PocManifest manifest = new PocManifest(List.of(ingress("api"), ingress("api2")), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("ingress"));
    }

    @Test
    void rejectsAnIngressContainerThatDeclaresAPort() {
        ManifestContainer badIngress = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        PocManifest manifest = new PocManifest(List.of(badIngress), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("must not declare a port"));
    }

    @Test
    void rejectsASidecarWithNoPort() {
        ManifestContainer badSidecar = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(ingress("api"), badSidecar), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("must declare a port"));
    }

    @Test
    void rejectsDuplicateContainerNames() {
        PocManifest manifest = new PocManifest(List.of(ingress("app"), sidecar("app", 9000)), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("more than once"));
    }

    @Test
    void rejectsAnInvalidContainerName() {
        PocManifest manifest = new PocManifest(List.of(ingress("Not_Valid!")), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("lowercase alphanumeric"));
    }

    @Test
    void rejectsMoreContainersThanTheConfiguredMaximum() {
        ManifestProperties tightLimit = new ManifestProperties(null, null, 1);
        ManifestValidator validatorWithTightLimit = new ManifestValidator(tightLimit);
        PocManifest manifest = new PocManifest(List.of(ingress("api"), sidecar("worker", 9000)), new Resources(null, null));

        assertThat(validatorWithTightLimit.validate(manifest)).anySatisfy(v -> assertThat(v).contains("at most 1"));
    }

    @Test
    void rejectsAReservedEnvVarName() {
        ManifestContainer container = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of("PORT", "8080"));
        PocManifest manifest = new PocManifest(List.of(container), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("PORT"));
    }

    @Test
    void rejectsAReservedEnvVarPrefix() {
        ManifestContainer container = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of("SVC_WORKER_URL", "x"));
        PocManifest manifest = new PocManifest(List.of(container), new Resources(null, null));

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("reserved prefix"));
    }

    @Test
    void reportsEveryViolationInOnePassRatherThanFailingFast() {
        ManifestContainer badIngress = new ManifestContainer("Bad Name", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of("PORT", "x"));
        ManifestContainer alsoIngress = new ManifestContainer("also-bad", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(badIngress, alsoIngress), new Resources(null, null));

        List<String> violations = validator.validate(manifest);

        // Multiple-ingress, bad name, port-on-ingress, and reserved-env-var — all four caught together.
        assertThat(violations).hasSizeGreaterThanOrEqualTo(4);
    }
}
