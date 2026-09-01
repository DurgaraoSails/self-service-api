package com.sails.ai.selfserviceapi.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Backs DeploymentStatusPoller — polling in-flight Cloud Build jobs for completion. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
