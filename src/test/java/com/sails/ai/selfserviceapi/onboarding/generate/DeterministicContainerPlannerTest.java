package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedService;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.DetectedStack;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DeterministicContainerPlannerTest {

    private final DeterministicContainerPlanner planner = new DeterministicContainerPlanner();

    @Test
    void aSingleFullyResolvedImportedServiceBecomesIngressPlusSidecars() {
        ImportedContainer web = new ImportedContainer("web", "apps/web/Dockerfile", "apps/web", null, null, "2", "1Gi", Map.of(), null);
        ImportedContainer worker = new ImportedContainer("worker", "apps/worker/Dockerfile", "apps/worker", 8081, null, null, null, Map.of(), null);
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                java.util.List.of(new ImportedService("svc", java.util.List.of(web, worker), null, null, null)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        Optional<PocManifest> plan = planner.plan(Map.of(), cloudBuildImport);

        assertThat(plan).isPresent();
        assertThat(plan.get().ingress().name()).isEqualTo("web");
        assertThat(plan.get().containers()).hasSize(2);
        assertThat(plan.get().containers().get(1).role()).isEqualTo(ContainerRole.SIDECAR);
        assertThat(plan.get().resources().cpu()).isEqualTo("2");
    }

    /**
     * cloudbuild.yaml gives no role signal, so declaration order decides by default — but when the
     * detected stacks make it clear which container is the browser-facing frontend, that one becomes
     * ingress even if a backend API was declared first.
     */
    @Test
    void prefersTheBrowserFacingContainerAsIngressOverDeclarationOrder() {
        ImportedContainer api = new ImportedContainer("api", "apps/api/Dockerfile", "apps/api", 8081, null, null, null, Map.of(), null);
        ImportedContainer web = new ImportedContainer("web", "apps/web/Dockerfile", "apps/web", null, null, null, null, Map.of(), null);
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                java.util.List.of(new ImportedService("svc", java.util.List.of(api, web), null, null, null)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        Map<String, DetectedStack> stacks = Map.of(
                "apps/api", new DetectedStack(StackKind.PYTHON_FASTAPI, "apps/api", Map.of(), java.util.List.of()),
                "apps/web", new DetectedStack(StackKind.VITE_SPA, "apps/web", Map.of(), java.util.List.of()));

        Optional<PocManifest> plan = planner.plan(stacks, cloudBuildImport);

        assertThat(plan).isPresent();
        assertThat(plan.get().ingress().name()).isEqualTo("web");
    }

    /** Two candidates that could both plausibly be a frontend is a genuine ambiguity — fall back rather than guess. */
    @Test
    void fallsBackToDeclarationOrderWhenMoreThanOneContainerLooksBrowserFacing() {
        ImportedContainer web1 = new ImportedContainer("web1", "apps/web1/Dockerfile", "apps/web1", null, null, null, null, Map.of(), null);
        ImportedContainer web2 = new ImportedContainer("web2", "apps/web2/Dockerfile", "apps/web2", 8081, null, null, null, Map.of(), null);
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                java.util.List.of(new ImportedService("svc", java.util.List.of(web1, web2), null, null, null)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        Map<String, DetectedStack> stacks = Map.of(
                "apps/web1", new DetectedStack(StackKind.VITE_SPA, "apps/web1", Map.of(), java.util.List.of()),
                "apps/web2", new DetectedStack(StackKind.ANGULAR_SPA, "apps/web2", Map.of(), java.util.List.of()));

        Optional<PocManifest> plan = planner.plan(stacks, cloudBuildImport);

        assertThat(plan).isPresent();
        assertThat(plan.get().ingress().name()).isEqualTo("web1");
    }

    @Test
    void importIsIgnoredWhenAnyContainerIsMissingADockerfileOrContext() {
        ImportedContainer unresolved = new ImportedContainer("web", null, null, null, null, null, null, Map.of(), null);
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                java.util.List.of(new ImportedService("svc", java.util.List.of(unresolved), null, null, null)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        Optional<PocManifest> plan = planner.plan(Map.of(), cloudBuildImport);

        assertThat(plan).isEmpty();
    }

    @Test
    void importIsIgnoredWhenThereIsMoreThanOneService() {
        ImportedContainer c = new ImportedContainer("web", "Dockerfile", ".", null, null, null, null, Map.of(), null);
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                java.util.List.of(new ImportedService("svc-a", java.util.List.of(c), null, null, null),
                        new ImportedService("svc-b", java.util.List.of(c), null, null, null)),
                java.util.List.of(), java.util.List.of(), java.util.List.of());

        Optional<PocManifest> plan = planner.plan(Map.of(), cloudBuildImport);

        assertThat(plan).isEmpty();
    }

    @Test
    void aSingleRecognizedComponentAtTheRepoRootBecomesTheDefaultAppContainer() {
        Map<String, DetectedStack> stacks = Map.of("", new DetectedStack(StackKind.NODE_SERVER, "", Map.of(), java.util.List.of("package.json")));

        Optional<PocManifest> plan = planner.plan(stacks, null);

        assertThat(plan).isPresent();
        assertThat(plan.get().containers()).singleElement().satisfies(c -> {
            assertThat(c.name()).isEqualTo("app");
            assertThat(c.dockerfile()).isEqualTo("Dockerfile");
            assertThat(c.context()).isEqualTo(".");
        });
    }

    @Test
    void aRecognizedComponentInASubdirectoryDoesNotQualify() {
        Map<String, DetectedStack> stacks = Map.of("apps/api", new DetectedStack(StackKind.PYTHON_FASTAPI, "apps/api", Map.of(), java.util.List.of()));

        Optional<PocManifest> plan = planner.plan(stacks, null);

        assertThat(plan).isEmpty();
    }

    @Test
    void multipleRecognizedComponentsDoNotQualify() {
        Map<String, DetectedStack> stacks = Map.of(
                "", new DetectedStack(StackKind.NODE_SERVER, "", Map.of(), java.util.List.of()),
                "apps/api", new DetectedStack(StackKind.PYTHON_FASTAPI, "apps/api", Map.of(), java.util.List.of()));

        Optional<PocManifest> plan = planner.plan(stacks, null);

        assertThat(plan).isEmpty();
    }

    @Test
    void noRecognizableShapeAtAllReturnsEmpty() {
        Map<String, DetectedStack> stacks = Map.of("", DetectedStack.unknown(""));

        Optional<PocManifest> plan = planner.plan(stacks, null);

        assertThat(plan).isEmpty();
    }
}
