package com.sails.ai.selfserviceapi.deploypipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.sails.ai.selfserviceapi.deploypipeline.build.BuildService;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Which executor bean Spring resolves is decided entirely by {@code @ConditionalOnProperty}, which
 * no other test exercises — a broken condition compiles, passes every unit test, and only shows up
 * as a context that will not start.
 *
 * <p>Both executors are registered here, and every case asserts on {@link PipelineExecutor} rather
 * than on a concrete class. That is what makes the assertions mean anything: with only the skipping
 * executor registered, "cloud-build does not get the skipping executor" would pass just as happily
 * if {@code CloudBuildPipelineExecutor}'s own condition were misspelled or deleted, and the real
 * failure — no bean matching, so {@link PipelineRunner}'s constructor injection fails and the
 * application does not boot — would ship green.
 *
 * <p>The case that matters most is an absent {@code pipeline.executor}. That fallback used to
 * belong to the local executor; when it was deleted, something had to claim it.
 */
class PipelineExecutorWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(BuildService.class, () -> mock(BuildService.class))
            .withBean(CloudRunService.class, () -> mock(CloudRunService.class))
            .withUserConfiguration(SkippingPipelineExecutor.class, CloudBuildPipelineExecutor.class);

    @Test
    void fallsBackToSkippingWhenNoExecutorIsConfiguredAtAll() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PipelineExecutor.class);
            assertThat(context).hasSingleBean(SkippingPipelineExecutor.class);
        });
    }

    @Test
    void resolvesSkippingWhenExplicitlyAskedFor() {
        contextRunner.withPropertyValues("pipeline.executor=skip")
                .run(context -> {
                    assertThat(context).hasSingleBean(PipelineExecutor.class);
                    assertThat(context).hasSingleBean(SkippingPipelineExecutor.class);
                });
    }

    /** cloud-build must not also get the skipping executor, or a real deploy would silently no-op. */
    @Test
    void resolvesCloudBuildAndOnlyCloudBuildWhenItIsSelected() {
        contextRunner.withPropertyValues("pipeline.executor=cloud-build")
                .run(context -> {
                    assertThat(context).hasSingleBean(PipelineExecutor.class);
                    assertThat(context).hasSingleBean(CloudBuildPipelineExecutor.class);
                    assertThat(context).doesNotHaveBean(SkippingPipelineExecutor.class);
                });
    }

    /**
     * The failure mode the whole class exists for: a value no condition claims leaves PipelineRunner
     * with nothing to inject. PipelineProperties rejects such a value at startup, but that guard
     * lives in a different class and could be relaxed without anyone noticing this depends on it.
     */
    @Test
    void leavesNoExecutorAtAllForAnUnrecognisedValue() {
        contextRunner.withPropertyValues("pipeline.executor=local")
                .run(context -> assertThat(context).doesNotHaveBean(PipelineExecutor.class));
    }
}
