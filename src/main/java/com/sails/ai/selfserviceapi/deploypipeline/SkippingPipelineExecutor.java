package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Satisfies the {@link PipelineExecutor} bean requirement when {@code pipeline.executor=skip} —
 * exists only so Spring has something to inject into {@link PipelineRunner}. {@link PipelineRunner}
 * checks {@code PipelineProperties.isSkip()} before ever calling GitHub or an executor, so neither
 * method here should actually run; they throw rather than silently pretend to have deployed
 * something.
 */
@Component
@ConditionalOnProperty(prefix = "pipeline", name = "executor", havingValue = "skip")
public class SkippingPipelineExecutor implements PipelineExecutor {

    @Override
    public String buildAndPushImage(GitHubRepoRef repo, String versionLabel, String pocSlug) {
        throw new IllegalStateException("SkippingPipelineExecutor should never be invoked — "
                + "PipelineRunner must short-circuit on PipelineProperties.isSkip() first.");
    }

    @Override
    public String deploy(String pocSlug, String image) {
        throw new IllegalStateException("SkippingPipelineExecutor should never be invoked — "
                + "PipelineRunner must short-circuit on PipelineProperties.isSkip() first.");
    }
}
