package com.sails.ai.selfserviceapi.deploypipeline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PipelinePropertiesTest {

    /**
     * The failure this guards against is silent, not loud. Spring picks the executor with
     * @ConditionalOnProperty, which cannot express "reject anything else" — a stale
     * PIPELINE_EXECUTOR=local matches no condition, falls through to the skipping executor's
     * matchIfMissing, and the app boots reporting every deploy SKIPPED while an operator believes
     * builds are running.
     */
    @Test
    void rejectsTheRemovedLocalExecutorByName() {
        assertThatThrownBy(() -> properties("local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("local")
                .hasMessageContaining("cloud-build")
                .hasMessageContaining("skip");
    }

    @Test
    void rejectsAnyUnrecognisedExecutor() {
        assertThatThrownBy(() -> properties("clod-buidl"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clod-buidl");
    }

    @Test
    void acceptsTheTwoSupportedExecutors() {
        assertThatCode(() -> properties("cloud-build")).doesNotThrowAnyException();
        assertThat(properties("cloud-build").isSkip()).isFalse();
        assertThat(properties("skip").isSkip()).isTrue();
    }

    /** Matches isSkip(), which has always compared case-insensitively. */
    @Test
    void acceptsAnExecutorInAnyCase() {
        assertThatCode(() -> properties("CLOUD-BUILD")).doesNotThrowAnyException();
        assertThat(properties("CLOUD-BUILD").isSkip()).isFalse();
        assertThat(properties("Skip").isSkip()).isTrue();
    }

    /**
     * Absent is not invalid: SkippingPipelineExecutor claims that case via matchIfMissing, so
     * throwing here would break the very configuration the fallback exists to support.
     */
    @Test
    void allowsNoExecutorAtAll() {
        assertThatCode(() -> properties(null)).doesNotThrowAnyException();
    }

    /**
     * The other half of matchIfMissing, and the assertion that matters: PipelineRunner and
     * PocDeploymentService both branch on isSkip(), so if absent did not read as skip they would
     * take the real deploy path against an executor that only throws — writing a release tag to the
     * POC's repository first, and leaving it behind to block that version label.
     */
    @Test
    void treatsAnAbsentExecutorAsSkipSoTheRunnerAgreesWithTheWiring() {
        assertThat(properties(null).isSkip()).isTrue();
    }

    /**
     * Present-but-empty is a different case and stays an error. @ConditionalOnProperty does not
     * apply matchIfMissing to it, so no executor bean matches and the context would fail on
     * PipelineRunner's injection — better to name the property than to let that be the symptom.
     */
    @Test
    void rejectsAnEmptyExecutorRatherThanReadingItAsAbsent() {
        assertThatThrownBy(() -> properties(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.executor");
    }

    // --- the build service account cloud-build needs ----------------------------------------

    /**
     * Blank stopped being a weaker fallback when the clone step began reading its token from Secret
     * Manager unconditionally: it means Cloud Build's own default service account, which holds no
     * secretAccessor grant on github-token, so every build would fail at its first step with an
     * error naming the secret rather than the property that was left unset.
     */
    @Test
    void rejectsCloudBuildWithNoBuildServiceAccount() {
        assertThatThrownBy(() -> properties("cloud-build", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.build-service-account")
                .hasMessageContaining("self-service-builder");

        assertThatThrownBy(() -> properties("cloud-build", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.build-service-account");
    }

    /** skip submits no builds at all, so it must not require an account to run without GCP. */
    @Test
    void allowsNoBuildServiceAccountWhenNothingIsBuilt() {
        assertThatCode(() -> properties("skip", null)).doesNotThrowAnyException();
        assertThatCode(() -> properties(null, null)).doesNotThrowAnyException();
    }

    // --- the deploy branch, if one is pinned -------------------------------------------------

    /**
     * The branch name is concatenated into the GitHub URI path so a slashed name keeps its slash
     * (see GitHubService.getBranchHeadSha), which makes its shape this record's business: ".."
     * would climb out of /repos/{owner}/{repo}, and a brace would be read as a URI variable.
     */
    @Test
    void rejectsADeployBranchThatIsNotAUsableRefName() {
        assertThatThrownBy(() -> propertiesDeployingFrom("../../other/repo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.deploy-branch");

        assertThatThrownBy(() -> propertiesDeployingFrom("release 2024"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.deploy-branch");

        assertThatThrownBy(() -> propertiesDeployingFrom("{main}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pipeline.deploy-branch");
    }

    @Test
    void acceptsTheBranchNamesPeopleActuallyUse() {
        assertThatCode(() -> propertiesDeployingFrom("main")).doesNotThrowAnyException();
        assertThatCode(() -> propertiesDeployingFrom("release/2024")).doesNotThrowAnyException();
        assertThatCode(() -> propertiesDeployingFrom("feature/ABC-123_thing")).doesNotThrowAnyException();
    }

    /** Blank is "not pinned", not an invalid name — the property is optional. */
    @Test
    void acceptsABlankDeployBranchAsMeaningUnpinned() {
        assertThatCode(() -> propertiesDeployingFrom("   ")).doesNotThrowAnyException();
        assertThat(propertiesDeployingFrom("   ").hasDeployBranch()).isFalse();
        assertThat(propertiesDeployingFrom(null).hasDeployBranch()).isFalse();
    }

    private static PipelineProperties propertiesDeployingFrom(String deployBranch) {
        return new PipelineProperties("cloud-build", "self-service-builder", "ghp_token", false, true,
                Duration.ofMinutes(20), Duration.ofSeconds(10), deployBranch);
    }

    private static PipelineProperties properties(String executor) {
        return properties(executor, "self-service-builder");
    }

    private static PipelineProperties properties(String executor, String buildServiceAccount) {
        return new PipelineProperties(executor, buildServiceAccount, "ghp_token", false, true,
                Duration.ofMinutes(20), Duration.ofSeconds(10), null);
    }
}
