package com.sails.ai.selfserviceapi.deploypipeline.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Backs every {@code @Async} method in the deploy pipeline — {@code PipelineRunner}
 * (deploy/redeploy/retry) and {@code PocRepoStatusService.refresh} (repository-snapshot refresh).
 *
 * <p>Bare {@code @EnableAsync} with no {@link Executor} bean falls back to
 * {@code SimpleAsyncTaskExecutor}: a new unbounded thread per call, no pool, no queue. That was
 * survivable while a human triggered one deploy at a time, but a refresh fires on every POC
 * creation and every deploy completion — an impatient admin or a bulk import can fan out enough of
 * those to matter. A bounded pool caps that; the queue absorbs a burst instead of spawning threads
 * for it.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String PIPELINE_EXECUTOR = "pipelineExecutor";

    @Bean(PIPELINE_EXECUTOR)
    public Executor pipelineExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("pipeline-");
        executor.initialize();
        return executor;
    }
}
