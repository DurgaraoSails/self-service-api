package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestValidatorTest {

    private final ManifestValidator validator = new ManifestValidator();

    private static ManifestContainer container(String name, ContainerRole role, Integer port) {
        return new ManifestContainer(name, role, "Dockerfile", ".", port, "/healthz", null, Map.of());
    }

    private static PocManifest manifestOf(ManifestContainer... containers) {
        return new PocManifest(PocManifest.API_VERSION, "Test", null, null,
                List.of(containers), Resources.defaults(), Scaling.defaults(), Platform.none());
    }

    @Test
    void acceptsASingleIngressContainer() {
        PocManifest manifest = manifestOf(container("app", ContainerRole.INGRESS, null));

        assertThatCode(() -> validator.validate(manifest)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAnIngressWithSidecars() {
        PocManifest manifest = manifestOf(
                container("web", ContainerRole.INGRESS, null),
                container("api", ContainerRole.SIDECAR, 8081),
                container("agent", ContainerRole.SIDECAR, 8082));

        assertThatCode(() -> validator.validate(manifest)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAManifestWithNoIngressContainer() {
        PocManifest manifest = manifestOf(container("api", ContainerRole.SIDECAR, 8081));

        assertThatThrownBy(() -> validator.validate(manifest))
                .isInstanceOf(InvalidPocManifestException.class)
                .hasMessageContaining("exactly one container must have role: ingress, found 0");
    }

    @Test
    void rejectsAManifestWithTwoIngressContainers() {
        PocManifest manifest = manifestOf(
                container("a", ContainerRole.INGRESS, null),
                container("b", ContainerRole.INGRESS, null));

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("found 2");
    }

    @Test
    void rejectsASidecarWithNoPort() {
        PocManifest manifest = manifestOf(
                container("web", ContainerRole.INGRESS, null),
                container("api", ContainerRole.SIDECAR, null));

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("sidecar \"api\" must declare a port");
    }

    @Test
    void rejectsAnIngressThatDeclaresAPort() {
        PocManifest manifest = manifestOf(container("web", ContainerRole.INGRESS, 8080));

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("must not declare a port");
    }

    @Test
    void rejectsDuplicateSidecarPorts() {
        PocManifest manifest = manifestOf(
                container("web", ContainerRole.INGRESS, null),
                container("api", ContainerRole.SIDECAR, 8081),
                container("agent", ContainerRole.SIDECAR, 8081));

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("sidecar port 8081 is declared by more than one container");
    }

    @Test
    void rejectsDuplicateContainerNames() {
        PocManifest manifest = manifestOf(
                container("web", ContainerRole.INGRESS, null),
                container("web", ContainerRole.SIDECAR, 8081));

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("is declared more than once");
    }

    @Test
    void rejectsAReservedEnvVarName() {
        ManifestContainer container = new ManifestContainer(
                "web", ContainerRole.INGRESS, "Dockerfile", ".", null, "/healthz", null, Map.of("PORT", "9000"));
        PocManifest manifest = manifestOf(container);

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("reserved env var \"PORT\"");
    }

    @Test
    void rejectsAnSvcPrefixedEnvVarName() {
        ManifestContainer container = new ManifestContainer(
                "web", ContainerRole.INGRESS, "Dockerfile", ".", null, "/healthz", null, Map.of("SVC_API_URL", "x"));
        PocManifest manifest = manifestOf(container);

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("reserved env var \"SVC_API_URL\"");
    }

    @Test
    void rejectsAContainerWithRepoSetSinceCrossRepoIsNotSupportedYet() {
        ManifestContainer container = new ManifestContainer(
                "web", ContainerRole.INGRESS, "Dockerfile", ".", null, "/healthz",
                "https://github.com/example-org/other-repo.git", Map.of());
        PocManifest manifest = manifestOf(container);

        assertThatThrownBy(() -> validator.validate(manifest))
                .hasMessageContaining("cross-repository containers aren't supported yet");
    }

    @Test
    void rejectsMoreThanEightContainers() {
        ManifestContainer[] containers = new ManifestContainer[9];
        containers[0] = container("ingress", ContainerRole.INGRESS, null);
        for (int i = 1; i < 9; i++) {
            containers[i] = container("sidecar" + i, ContainerRole.SIDECAR, 8080 + i);
        }

        assertThatThrownBy(() -> validator.validate(manifestOf(containers)))
                .hasMessageContaining("Cloud Run allows at most 8");
    }

    @Test
    void collectsEveryViolationInOneException() {
        PocManifest manifest = manifestOf(
                container("a", ContainerRole.INGRESS, null),
                container("b", ContainerRole.INGRESS, null),
                container("c", ContainerRole.SIDECAR, null));

        assertThatThrownBy(() -> validator.validate(manifest))
                .satisfies(e -> {
                    String message = e.getMessage();
                    assertThat(message).contains("found 2");
                    assertThat(message).contains("sidecar \"c\" must declare a port");
                });
    }
}
