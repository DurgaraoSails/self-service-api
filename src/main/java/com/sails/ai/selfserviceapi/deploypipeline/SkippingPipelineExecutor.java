package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Satisfies the {@link PipelineExecutor} bean requirement when {@code pipeline.executor=skip} —
 * exists only so Spring has something to inject into {@link PipelineRunner}. {@link PipelineRunner}
 * checks {@code PipelineProperties.isSkip()} before ever calling GitHub or an executor, so neither
 * method here should actually run; they throw rather than silently pretend to have deployed
 * something.
 *
 * <p>{@code matchIfMissing} makes this the fallback when {@code pipeline.executor} is not set at
 * all, which it inherited from the deleted local executor. Something must claim that case: with no
 * bean matching, {@link PipelineRunner}'s constructor injection fails and the whole context refuses
 * to start. Skipping is the right default anyway — an app booted with no pipeline configuration
 * should decline to deploy rather than assume it may build.
 */
@Component
@ConditionalOnProperty(prefix = "pipeline", name = "executor", havingValue = "skip", matchIfMissing = true)
public class SkippingPipelineExecutor implements PipelineExecutor {

    @Override
    public Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest) {
        throw new IllegalStateException("SkippingPipelineExecutor should never be invoked — "
                + "PipelineRunner must short-circuit on PipelineProperties.isSkip() first.");
    }

    @Override
    public String deploy(String pocSlug, PocManifest manifest, Map<String, String> imagesByContainer) {
        throw new IllegalStateException("SkippingPipelineExecutor should never be invoked — "
                + "PipelineRunner must short-circuit on PipelineProperties.isSkip() first.");
    }
}
