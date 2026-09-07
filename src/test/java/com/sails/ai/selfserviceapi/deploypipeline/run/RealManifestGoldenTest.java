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
        assertThat(new ManifestValidator(new ManifestProperties(null, null, 8)).validate(manifest)).isEmpty();

        CloudRunDeployCommandBuilder builder = new CloudRunDeployCommandBuilder(
                new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com"));

        List<String> service = builder.buildServiceArgs(manifest);
        List<String> args = builder.buildContainerArgs("poc-testbed-one", manifest,
                Map.of("frontend", "img/frontend:1.0.5", "backend", "img/backend:1.0.5"));

        assertThat(service).containsExactly("--min-instances=0", "--max-instances=3");

        // The one invariant Cloud Run rejects the whole revision over.
        assertThat(args.stream().filter(arg -> arg.startsWith("--port=") && !arg.equals("--port=default")).toList())
                .containsExactly("--port=8080");

        assertThat(args).containsExactly(
                "--container=frontend", "--image=img/frontend:1.0.5", "--port=8080", "--cpu=1", "--memory=1Gi",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=8080",
                "--depends-on=backend",
                "--set-env-vars=^;^PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;SVC_BACKEND_URL=http://localhost:8081",
                "--container=backend", "--image=img/backend:1.0.5", "--port=default",
                "--startup-probe=httpGet.path=/healthz,httpGet.port=8081",
                "--set-env-vars=^;^PLATFORM_API_URL=https://api.example.com;POC_SLUG=poc-testbed-one"
                        + ";PORTAL_ORIGIN=https://portal.example.com;PORT=8081");
    }
}
