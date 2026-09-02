package com.sails.ai.selfserviceapi.deploypipeline.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Backs PipelineRunner — deploy/redeploy/retry must return before the build finishes. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
