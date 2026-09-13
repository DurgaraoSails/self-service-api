package com.sails.ai.selfserviceapi.deploypipeline.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParser;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The real poc-integration-testbed manifest, all the way from YAML to argv. Every other test here
 * builds a ManifestContainer by hand, which cannot catch a parse-level mistake — and the failure
 * this whole contract was written after ("Revision template should contain exactly one container
 * with an exposed port") was only ever visible in the finished command.
 */
class RealManifestGoldenTest {

    private static final String TESTBED_MANIFEST = """
            apiVersion: sails.poc/v1
            name: POC Integration Testbed
            description: Sample chatbot POC.
            team: platform-testing

            containers:
              - name: frontend
                role: ingress
                dockerfile: apps/frontend/Dockerfile
                context: apps/frontend
                port: 8080
                health: /healthz

              - name: backend
                role: sidecar
                dockerfile: apps/backend/Dockerfile
                context: apps/backend
                port: 8081
                health: /healthz

            resources: { cpu: "1", memory: "1Gi" }
            scaling:   { min: 0, max: 3 }

            platform:
              database: { enabled: false }
              files:    { enabled: true }
            """;

    @Test
    void deploysTheTestbedWithExactlyOneExposedPortAndEveryPlatformValueInPlace() {
        PocManifest manifest = new ManifestParser().parse(TESTBED_MANIFEST);
        assertThat(new ManifestValidator(new ManifestProperties(null, null, 8),
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com")).validate(manifest)).isEmpty();

        CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com"));

        List<String> service = builder.buildServiceArgs(manifest);
        List<String> args = builder.buildContainerArgs("poc-testbed-one", manifest,
                Map.of("frontend", "img/frontend:1.0.5", "backend", "img/backend:1.0.5"));

        assertThat(service).containsExactly("--min-instances=0", "--max-instances=3");

        // The one invariant Cloud Run rejects the whole revision over.
        assertThat(args.stream().filter(arg -> arg.startsWith("--port=")).toList())
                .containsExactly("--port=8080");

        assertThat(args).containsExactly(
                "--container=frontend", "--image=img/frontend:1.0.5", "--port=8080", "--cpu=1", "--memory=1Gi",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=8080,timeoutSeconds=5,periodSeconds=10,failureThreshold=12",
                "--depends-on=backend",
                "--set-env-vars=^;^PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;SVC_BACKEND_URL=http://localhost:8081",
                "--container=backend", "--image=img/backend:1.0.5",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=8081,timeoutSeconds=5,periodSeconds=10,failureThreshold=12",
                "--set-env-vars=^;^PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;PORT=8081");
    }

    /**
     * The same two containers, written by a team that never edited its application to suit this
     * platform: a Vite frontend on 3000 that reads BACKEND_API_URL, and a Spring backend that reads
     * SERVER_PORT. Neither name is one the platform chose, and no source change makes them work —
     * only the manifest changed.
     */
    private static final String ALIASED_MANIFEST = """
            containers:
              - name: frontend
                role: ingress
                dockerfile: apps/frontend/Dockerfile
                context: apps/frontend
                port: 3000
                health: /healthz
                env:
                  BACKEND_API_URL: ${services.backend.url}
                  PUBLIC_BASE: ${services.backend.url}/api/v1

              - name: backend
                role: sidecar
                dockerfile: apps/backend/Dockerfile
                context: apps/backend
                port: 8081
                health: /healthz
                env:
                  SERVER_PORT: ${self.port}
                  ALLOWED_ORIGIN: ${portal.origin}
                  JWKS_ISSUER: ${platform.apiUrl}
                  EXPECTED_AUDIENCE: poc:${poc.slug}
            """;

    @Test
    void deploysAnAliasedManifestWithEveryPlaceholderResolvedAndEveryPlatformNameStillPresent() {
        PocManifest manifest = new ManifestParser().parse(ALIASED_MANIFEST);
        assertThat(new ManifestValidator(new ManifestProperties(null, null, 8),
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com")).validate(manifest)).isEmpty();

        CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com"));

        List<String> args = builder.buildContainerArgs("poc-testbed-one", manifest,
                Map.of("frontend", "img/frontend:2.0.0", "backend", "img/backend:2.0.0"));

        // Still exactly one exposed port, and it is the one the manifest named rather than 8080.
        assertThat(args.stream().filter(arg -> arg.startsWith("--port=")).toList())
                .containsExactly("--port=3000");

        assertThat(args).containsExactly(
                "--container=frontend", "--image=img/frontend:2.0.0", "--port=3000",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=3000,timeoutSeconds=5,periodSeconds=10,failureThreshold=12",
                "--depends-on=backend",
                "--set-env-vars=^;^BACKEND_API_URL=http://localhost:8081"
                        + ";PUBLIC_BASE=http://localhost:8081/api/v1"
                        + ";PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;SVC_BACKEND_URL=http://localhost:8081",
                "--container=backend", "--image=img/backend:2.0.0",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=8081,timeoutSeconds=5,periodSeconds=10,failureThreshold=12",
                "--set-env-vars=^;^SERVER_PORT=8081"
                        + ";ALLOWED_ORIGIN=https://portal.example.com"
                        + ";JWKS_ISSUER=https://api.example.com"
                        + ";EXPECTED_AUDIENCE=poc:poc-testbed-one"
                        + ";PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;PORT=8081");
    }
}
