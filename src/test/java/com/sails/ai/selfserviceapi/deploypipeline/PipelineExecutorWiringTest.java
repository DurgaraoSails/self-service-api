package com.sails.ai.selfserviceapi.deploypipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which executor bean Spring resolves is decided entirely by {@code @ConditionalOnProperty}, which
 * no other test exercises — a broken condition compiles, passes every unit test, and only shows up
 * as a context that will not start.
 *
 * <p>The case that matters most is an absent {@code pipeline.executor}. That fallback used to
 * belong to the local executor; when it was deleted, something had to claim it, because with no
 * bean matching, {@link PipelineRunner}'s constructor injection fails and the application does not
 * boot at all.
 */
class PipelineExecutorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(SkippingPipelineExecutor.class);

    @Test
    void fallsBackToSkippingWhenNoExecutorIsConfiguredAtAll() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(SkippingPipelineExecutor.class));
    }

    @Test
    void resolvesSkippingWhenExplicitlyAskedFor() {
        contextRunner.withPropertyValues("pipeline.executor=skip")
                .run(context -> assertThat(context).hasSingleBean(SkippingPipelineExecutor.class));
    }

    /** cloud-build must not also get the skipping executor, or a real deploy would silently no-op. */
    @Test
    void doesNotRegisterSkippingWhenCloudBuildIsSelected() {
        contextRunner.withPropertyValues("pipeline.executor=cloud-build")
                .run(context -> assertThat(context).doesNotHaveBean(SkippingPipelineExecutor.class));
    }
}
