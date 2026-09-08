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
        assertThat(properties("cloud-build").isCloudBuild()).isTrue();
        assertThat(properties("skip").isSkip()).isTrue();
    }

    /** Matches isCloudBuild()/isSkip(), which have always compared case-insensitively. */
    @Test
    void acceptsAnExecutorInAnyCase() {
        assertThat(properties("CLOUD-BUILD").isCloudBuild()).isTrue();
        assertThat(properties("Skip").isSkip()).isTrue();
    }

    /**
     * Absent is not invalid: SkippingPipelineExecutor claims that case via matchIfMissing, so
     * throwing here would break the very configuration the fallback exists to support.
     */
    @Test
    void allowsNoExecutorAtAll() {
        assertThatCode(() -> properties(null)).doesNotThrowAnyException();
        assertThat(properties(null).isCloudBuild()).isFalse();
    }

    private static PipelineProperties properties(String executor) {
        return new PipelineProperties(executor, "self-service-builder", "ghp_token", false, true,
                Duration.ofMinutes(20), Duration.ofSeconds(10));
    }
}
