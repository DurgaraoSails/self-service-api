package com.sails.ai.selfserviceapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** Backs DeploymentOrchestrator — POC creation/redeploy must not block on the pipeline. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
