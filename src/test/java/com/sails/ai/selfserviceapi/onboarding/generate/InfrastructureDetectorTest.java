package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InfrastructureDetectorTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final RepoFileReader fileReader = new RepoFileReader(gitHubService, REPO, SHA);
    private final InfrastructureDetector detector = new InfrastructureDetector();

    private RepoLayout layoutOf(String... paths) {
        return RepoLayout.of(new GitHubTree(
                java.util.Arrays.stream(paths).map(p -> new GitHubTreeEntry(p, "blob", 10L)).toList(), false));
    }

    @Test
    void flagsAPostgresDependencyInPackageJson() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"pg\":\"^8.0.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).hasSize(2);
        assertThat(notices).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo(GenerationNoticeCode.EXTERNAL_INFRASTRUCTURE_DETECTED);
            assertThat(n.message()).contains("PostgreSQL");
            assertThat(n.path()).isEqualTo("package.json");
        });
    }

    @Test
    void alsoReportsWhatAccessTheServiceAccountWouldNeedForADetectedSignal() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"pg\":\"^8.0.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo(GenerationNoticeCode.SERVICE_ACCOUNT_ACCESS_NEEDED);
            assertThat(n.message()).contains("cloudsql.client");
            assertThat(n.path()).isEqualTo("package.json");
        });
    }

    @Test
    void namesTheAccessHintAsNoGcpIamRoleWhenTheCategoryHasNoPlatformNativeOption() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"mongoose\":\"^8.0.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo(GenerationNoticeCode.SERVICE_ACCOUNT_ACCESS_NEEDED);
            assertThat(n.message()).contains("no platform IAM role applies");
        });
    }

    @Test
    void flagsARedisDependencyInRequirementsTxt() {
        when(gitHubService.getFileContent(REPO, SHA, "requirements.txt")).thenReturn(Optional.of("redis==5.0.0\nfastapi\n"));

        List<GenerationNotice> notices = detector.detect(layoutOf("requirements.txt"), fileReader);

        assertThat(notices).anyMatch(n -> n.message().contains("Redis"));
    }

    @Test
    void namesThePlatformDoesNotProvisionItAutomatically() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"mongoose\":\"^8.0.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).anySatisfy(n -> assertThat(n.message()).contains("does not provision"));
    }

    @Test
    void doesNotFlagAnOrdinaryDependencyManifestWithNoKnownInfraSignal() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"express\":\"^4.18.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).isEmpty();
    }

    @Test
    void doesNotScanNonDependencyManifestFiles() {
        when(gitHubService.getFileContent(REPO, SHA, "README.md")).thenReturn(Optional.of("This app uses redis heavily."));

        List<GenerationNotice> notices = detector.detect(layoutOf("README.md"), fileReader);

        assertThat(notices).isEmpty();
    }

    @Test
    void reportsEachInfraCategoryAtMostOncePerFileEvenWithMultipleMatchingSignals() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"ioredis\":\"^5.0.0\",\"redis\":\"^4.0.0\"}}"));

        List<GenerationNotice> notices = detector.detect(layoutOf("package.json"), fileReader);

        assertThat(notices).hasSize(2);
    }

    @Test
    void scansADependencyManifestInASubdirectory() {
        when(gitHubService.getFileContent(REPO, SHA, "apps/api/requirements.txt"))
                .thenReturn(Optional.of("psycopg2-binary==2.9.9\n"));

        List<GenerationNotice> notices = detector.detect(layoutOf("apps/api/requirements.txt"), fileReader);

        assertThat(notices).hasSize(2);
        assertThat(notices).allSatisfy(n -> assertThat(n.path()).isEqualTo("apps/api/requirements.txt"));
    }
}
