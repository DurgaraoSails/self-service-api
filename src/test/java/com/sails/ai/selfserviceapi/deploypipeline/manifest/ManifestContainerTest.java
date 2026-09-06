package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for a real deploy failure: dockerfilePath() must resolve dockerfile against
 * its OWN container's context, not independently against the repo root — a container with
 * context "worker" and the default dockerfile "Dockerfile" must build from "worker/Dockerfile",
 * not look for a Dockerfile at the repo root that was never there.
 */
class ManifestContainerTest {

    @Test
    void resolvesTheDefaultDockerfileAgainstTheRootContext() {
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());

        assertThat(app.dockerfilePath()).isEqualTo("Dockerfile");
    }

    @Test
    void resolvesTheDefaultDockerfileAgainstANonRootContext() {
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile", "worker", 9000, Map.of());

        assertThat(worker.dockerfilePath()).isEqualTo("worker/Dockerfile");
    }

    @Test
    void resolvesANonDefaultDockerfileNameAgainstItsContext() {
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "Dockerfile.worker", "services/worker", 9000, Map.of());

        assertThat(worker.dockerfilePath()).isEqualTo("services/worker/Dockerfile.worker");
    }
}
