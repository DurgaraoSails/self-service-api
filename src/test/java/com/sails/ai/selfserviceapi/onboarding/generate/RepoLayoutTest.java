package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import org.junit.jupiter.api.Test;

class RepoLayoutTest {

    private static GitHubTree treeOf(String... paths) {
        return new GitHubTree(java.util.Arrays.stream(paths).map(p -> new GitHubTreeEntry(p, "blob", 10L)).toList(), false);
    }

    @Test
    void findsEveryDockerfileShape() {
        RepoLayout layout = RepoLayout.of(treeOf(
                "Dockerfile", "apps/web/Dockerfile.prod", "apps/api/api.Dockerfile", "tools/Containerfile",
                "apps/other/notes.txt"));

        assertThat(layout.dockerfiles()).containsExactlyInAnyOrder(
                "Dockerfile", "apps/web/Dockerfile.prod", "apps/api/api.Dockerfile", "tools/Containerfile");
    }

    @Test
    void groupsDockerfilesByDirectory() {
        RepoLayout layout = RepoLayout.of(treeOf("Dockerfile", "apps/web/Dockerfile"));

        assertThat(layout.directoriesWithDockerfile()).containsExactlyInAnyOrder("", "apps/web");
    }

    @Test
    void tracksDirectoriesWithADockerignore() {
        RepoLayout layout = RepoLayout.of(treeOf("apps/web/.dockerignore", "apps/web/Dockerfile"));

        assertThat(layout.directoriesWithDockerignore()).containsExactly("apps/web");
    }

    @Test
    void findsCloudbuildFilesInSeveralNamingShapes() {
        RepoLayout layout = RepoLayout.of(treeOf(
                "cloudbuild.yaml", "cloudbuild.yml", "cloudbuild.json", "cloudbuild-prod.yaml",
                "deploy/staging.cloudbuild.yaml", "not-cloudbuild.txt"));

        assertThat(layout.cloudbuildFiles()).containsExactlyInAnyOrder(
                "cloudbuild.yaml", "cloudbuild.yml", "cloudbuild.json", "cloudbuild-prod.yaml",
                "deploy/staging.cloudbuild.yaml");
    }

    @Test
    void findsComposeFiles() {
        RepoLayout layout = RepoLayout.of(treeOf("docker-compose.yml", "compose.yaml", "package.json"));

        assertThat(layout.composeFiles()).containsExactlyInAnyOrder("docker-compose.yml", "compose.yaml");
    }

    @Test
    void groupsBasenamesByDirectoryUpToDepthFour() {
        RepoLayout layout = RepoLayout.of(treeOf(
                "package.json",
                "apps/web/package.json",
                "apps/web/src/deep/very/deep/file.ts")); // directory "apps/web/src/deep/very/deep" is depth 5

        assertThat(layout.basenamesIn("")).contains("package.json");
        assertThat(layout.basenamesIn("apps/web")).contains("package.json");
        assertThat(layout.basenamesIn("apps/web/src/deep/very/deep")).isEmpty();
    }

    /** A vendored/build-output Dockerfile must not count as real evidence of anything. */
    @Test
    void excludesVendorDirectoriesEntirely() {
        RepoLayout layout = RepoLayout.of(treeOf(
                "node_modules/pkg/Dockerfile", "vendor/lib/cloudbuild.yaml", "apps/web/dist/package.json",
                "package.json"));

        assertThat(layout.dockerfiles()).isEmpty();
        assertThat(layout.cloudbuildFiles()).isEmpty();
        assertThat(layout.basenamesIn("apps/web/dist")).isEmpty();
        assertThat(layout.basenamesIn("")).containsExactly("package.json");
    }

    @Test
    void basenamesInAnUnknownDirectoryIsEmptyNotNull() {
        RepoLayout layout = RepoLayout.of(treeOf("package.json"));

        assertThat(layout.basenamesIn("nowhere")).isEmpty();
    }
}
