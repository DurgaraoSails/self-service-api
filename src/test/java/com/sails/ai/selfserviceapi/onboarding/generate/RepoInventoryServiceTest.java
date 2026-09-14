package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepoInventoryServiceTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final RepoInventoryService service = new RepoInventoryService(gitHubService);

    private void stubFile(String path, String content) {
        when(gitHubService.getFileContent(REPO, SHA, path)).thenReturn(Optional.of(content));
    }

    @Test
    void ranksDockerfilesAheadOfEverythingElse() {
        GitHubTree tree = new GitHubTree(List.of(
                new GitHubTreeEntry("README.md", "blob", 10L),
                new GitHubTreeEntry("apps/web/Dockerfile", "blob", 10L),
                new GitHubTreeEntry("package.json", "blob", 10L)), false);
        when(gitHubService.listTree(REPO, SHA)).thenReturn(tree);
        stubFile("README.md", "readme");
        stubFile("apps/web/Dockerfile", "FROM node");
        stubFile("package.json", "{}");

        RepoInventory inventory = service.inventory(REPO, SHA);

        assertThat(inventory.evidenceFiles()).extracting(RepoInventory.EvidenceFile::path)
                .containsExactly("apps/web/Dockerfile", "package.json", "README.md");
    }

    /** Dockerfiles rank first (they decide container shape directly); an existing manifest ranks ahead of other evidence. */
    @Test
    void anExistingManifestRanksAheadOfOtherNonDockerfileEvidence() {
        GitHubTree tree = new GitHubTree(List.of(
                new GitHubTreeEntry("Dockerfile", "blob", 10L),
                new GitHubTreeEntry("package.json", "blob", 10L),
                new GitHubTreeEntry("poc.yaml", "blob", 10L)), false);
        when(gitHubService.listTree(REPO, SHA)).thenReturn(tree);
        stubFile("Dockerfile", "FROM node");
        stubFile("package.json", "{}");
        stubFile("poc.yaml", "containers: []");

        RepoInventory inventory = service.inventory(REPO, SHA);

        assertThat(inventory.evidenceFiles()).extracting(RepoInventory.EvidenceFile::path)
                .containsExactly("Dockerfile", "poc.yaml", "package.json");
    }

    @Test
    void neverReadsMoreThanTheFileCap() {
        List<GitHubTreeEntry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String path = "apps/svc" + i + "/Dockerfile";
            entries.add(new GitHubTreeEntry(path, "blob", 10L));
            stubFile(path, "FROM node");
        }
        when(gitHubService.listTree(REPO, SHA)).thenReturn(new GitHubTree(entries, false));

        RepoInventory inventory = service.inventory(REPO, SHA);

        assertThat(inventory.evidenceFiles()).hasSizeLessThanOrEqualTo(RepoInventoryService.MAX_FILES);
    }

    @Test
    void neverExceedsTheTotalByteCap() {
        List<GitHubTreeEntry> entries = new ArrayList<>();
        String bigContent = "x".repeat(50_000);
        for (int i = 0; i < 6; i++) {
            String path = "apps/svc" + i + "/Dockerfile";
            entries.add(new GitHubTreeEntry(path, "blob", (long) bigContent.length()));
            stubFile(path, bigContent);
        }
        when(gitHubService.listTree(REPO, SHA)).thenReturn(new GitHubTree(entries, false));

        RepoInventory inventory = service.inventory(REPO, SHA);

        int totalBytes = inventory.evidenceFiles().stream().mapToInt(f -> f.content().length()).sum();
        assertThat(totalBytes).isLessThanOrEqualTo(RepoInventoryService.MAX_TOTAL_BYTES);
    }

    /** A single oversized file must not crowd out everything else's share of the byte cap. */
    @Test
    void skipsASingleFileLargerThanTheCap() {
        String huge = "x".repeat(70_000);
        GitHubTree tree = new GitHubTree(List.of(
                new GitHubTreeEntry("Dockerfile", "blob", (long) huge.length()),
                new GitHubTreeEntry("package.json", "blob", 10L)), false);
        when(gitHubService.listTree(REPO, SHA)).thenReturn(tree);
        stubFile("package.json", "{}");

        RepoInventory inventory = service.inventory(REPO, SHA);

        assertThat(inventory.evidenceFiles()).extracting(RepoInventory.EvidenceFile::path)
                .containsExactly("package.json");
    }

    @Test
    void surfacesTreeTruncationRatherThanHidingIt() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(new GitHubTree(List.of(), true));

        assertThat(service.inventory(REPO, SHA).treeTruncated()).isTrue();
    }
}
