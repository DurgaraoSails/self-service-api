package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.generate.ManifestMerger.MergeResult;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestMergerTest {

    private final ManifestMerger merger = new ManifestMerger();

    private ManifestContainer container(String name, Integer port, Map<String, String> env) {
        return new ManifestContainer(name, ContainerRole.INGRESS, "Dockerfile", ".", port, env);
    }

    @Test
    void withNoImportItOnlyNormalizesBlankDockerfilePaths() {
        ManifestContainer bare = new ManifestContainer("app", ContainerRole.INGRESS, "", "apps/api", null, Map.of());
        PocManifest draft = new PocManifest(List.of(bare), new Resources(null, null));

        PocManifest merged = merger.applyOverlay(draft, null);

        assertThat(merged.containers()).singleElement()
                .satisfies(c -> assertThat(c.dockerfile()).isEqualTo("apps/api/Dockerfile"));
    }

    @Test
    void anImportedContainerMatchedByNameOverridesPortAndEnvAndAddsSecretRequirements() {
        ManifestContainer draftContainer = container("app", 3000, Map.of("LOG_LEVEL", "debug"));
        PocManifest draft = new PocManifest(List.of(draftContainer), new Resources(null, null));

        ImportedContainer imported = new ImportedContainer("app", null, null, 8080, null, null, null,
                Map.of("NODE_ENV", "production"), List.of("OPENAI_API_KEY"));
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                List.of(new ImportedService("app-service", List.of(imported), null, null, null)),
                List.of(), List.of(), List.of());

        MergeResult result = merger.merge(draft, cloudBuildImport);

        ManifestContainer merged = result.manifest().containers().get(0);
        assertThat(merged.port()).isEqualTo(8080);
        assertThat(merged.env()).containsEntry("NODE_ENV", "production").containsEntry("LOG_LEVEL", "debug");
        assertThat(merged.requires()).anyMatch(r -> r.name().equals("OPENAI_API_KEY") && r.secret());
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.IMPORT_OVERRODE_DRAFT));
    }

    @Test
    void anImportedContainerMatchesByDockerfileAndContextWhenNamesDiffer() {
        ManifestContainer draftContainer = new ManifestContainer("web", ContainerRole.INGRESS,
                "apps/web/Dockerfile", "apps/web", null, Map.of());
        PocManifest draft = new PocManifest(List.of(draftContainer), new Resources(null, null));

        ImportedContainer imported = new ImportedContainer("frontend", "apps/web/Dockerfile", "apps/web",
                8080, null, "1", "512Mi", Map.of(), List.of());
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                List.of(new ImportedService("svc", List.of(imported), null, null, null)),
                List.of(), List.of(), List.of());

        MergeResult result = merger.merge(draft, cloudBuildImport);

        assertThat(result.manifest().containers()).singleElement()
                .satisfies(c -> assertThat(c.port()).isEqualTo(8080));
        assertThat(result.manifest().resources().cpu()).isEqualTo("1");
        assertThat(result.manifest().resources().memory()).isEqualTo("512Mi");
    }

    @Test
    void anUnmatchedImportedContainerIsLeftUnappliedRatherThanForceAdded() {
        ManifestContainer draftContainer = container("app", null, Map.of());
        PocManifest draft = new PocManifest(List.of(draftContainer), new Resources(null, null));

        ImportedContainer imported = new ImportedContainer("worker", "apps/worker/Dockerfile", "apps/worker",
                8081, null, null, null, Map.of(), List.of());
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                List.of(new ImportedService("svc", List.of(imported), null, null, null)),
                List.of(), List.of(), List.of());

        MergeResult result = merger.merge(draft, cloudBuildImport);

        assertThat(result.manifest().containers()).hasSize(1);
        assertThat(result.manifest().containers().get(0).name()).isEqualTo("app");
    }

    @Test
    void moreThanEightContainersAreCappedWithAWarningNotice() {
        List<ManifestContainer> containers = java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> container("app-" + i, null, Map.of()))
                .toList();
        PocManifest draft = new PocManifest(containers, new Resources(null, null));

        MergeResult result = merger.merge(draft, null);

        assertThat(result.manifest().containers()).hasSize(8);
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.TOO_MANY_COMPONENTS));
    }

    @Test
    void scalingComesFromTheImportedServiceWhenPresent() {
        ManifestContainer draftContainer = container("app", null, Map.of());
        PocManifest draft = new PocManifest(List.of(draftContainer), new Resources(null, null));
        CloudBuildImport cloudBuildImport = new CloudBuildImport("cloudbuild.yaml",
                List.of(new ImportedService("svc", List.of(), 1, 5, null)), List.of(), List.of(), List.of());

        MergeResult result = merger.merge(draft, cloudBuildImport);

        assertThat(result.manifest().scaling().min()).isEqualTo(1);
        assertThat(result.manifest().scaling().max()).isEqualTo(5);
    }
}
