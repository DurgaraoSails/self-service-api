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

    private static ManifestContainer ingress(String name, int port) {
        return new ManifestContainer(name, ContainerRole.INGRESS, "Dockerfile", ".", port, Map.of());
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
        PocManifest manifest = new PocManifest(List.of(ingress("api", 8080), sidecar("worker", 9000)), new Resources(null, null));

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

    /**
     * A sole ingress container deploys through the plain --image= form, which Cloud Run defaults
     * to port 8080 for regardless — a declared port there is inert, not wrong.
     */
    @Test
    void ignoresAPortDeclaredOnASoleIngressContainer() {
        ManifestContainer ingressWithPort = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        PocManifest manifest = new PocManifest(List.of(ingressWithPort), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }

    /**
     * Cloud Run has no default port for a multi-container service's ingress, but the platform owns
     * that value (poc-runtime.ingress-port) rather than making every manifest restate it. Requiring
     * it here would reject every multi-container repo already written against poc-platform-sdk's
     * published schema, for a value the deploy can always supply itself.
     */
    @Test
    void acceptsAMultiContainerManifestWhoseIngressDeclaresNoPort() {
        PocManifest manifest = new PocManifest(List.of(ingress("api"), sidecar("worker", 9000)), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }

    /** Permitted, not forbidden — a manifest that names its own ingress port still deploys with it. */
    @Test
    void acceptsAnIngressContainerThatDeclaresItsOwnPort() {
        PocManifest manifest = new PocManifest(List.of(ingress("api", 3000), sidecar("worker", 9000)), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void rejectsASidecarWithNoPort() {
        ManifestContainer badSidecar = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(ingress("api", 8080), badSidecar), new Resources(null, null));

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
    void acceptsAManifestWithValidScaling() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null), new Scaling(1, 5), PlatformConfig.none());

        assertThat(validator.validate(manifest)).isEmpty();
    }

    @Test
    void rejectsANegativeScalingMin() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null), new Scaling(-1, 5), PlatformConfig.none());

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("scaling.min"));
    }

    @Test
    void rejectsAScalingMaxOfZero() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null), new Scaling(0, 0), PlatformConfig.none());

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("scaling.max"));
    }

    @Test
    void rejectsAScalingMinGreaterThanMax() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null), new Scaling(5, 1), PlatformConfig.none());

        assertThat(validator.validate(manifest)).anySatisfy(v -> assertThat(v).contains("must not exceed"));
    }

    @Test
    void reportsEveryViolationInOnePassRatherThanFailingFast() {
        ManifestContainer badIngress = new ManifestContainer("Bad Name", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of("PORT", "x"));
        ManifestContainer alsoIngress = new ManifestContainer("also-bad", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        PocManifest manifest = new PocManifest(List.of(badIngress, alsoIngress), new Resources(null, null));

        List<String> violations = validator.validate(manifest);

        // Multiple-ingress, bad name, and reserved-env-var — all three caught together. (Neither
        // container's port is a violation here: no sidecar exists, so a declared port is merely
        // inert rather than wrong, and a missing one isn't required.)
        assertThat(violations).hasSizeGreaterThanOrEqualTo(3);
    }

    /**
     * Rejected by name rather than ignored: every container builds from the POC's own repo, so a
     * repo: key changes nothing about what deploys — and silently dropping it is how an author
     * ends up believing their second repository was built.
     */
    @Test
    void rejectsAContainerThatDeclaresItsOwnRepository() {
        ManifestContainer crossRepo = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null,
                Map.of(), null, "github.com/acme/other");
        PocManifest manifest = new PocManifest(List.of(crossRepo), new Resources(null, null));

        assertThat(validator.validate(manifest))
                .anySatisfy(violation -> assertThat(violation).contains("cross-repository containers aren't supported yet"));
    }

    @Test
    void acceptsAContainerThatDeclaresNoRepository() {
        PocManifest manifest = new PocManifest(List.of(ingress("app")), new Resources(null, null));

        assertThat(validator.validate(manifest)).isEmpty();
    }
}
