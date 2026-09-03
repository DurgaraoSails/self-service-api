package com.sails.ai.selfserviceapi.poc.service;

import java.util.List;

/**
 * The per-container detail a BUILD_AND_DEPLOY reports alongside SUCCEEDED — null for every other
 * status transition, and null even on a REDEPLOY's SUCCEEDED, since a redeploy rebuilds nothing
 * and its version already carries this from the original build.
 */
public record PocBuildOutcome(String manifestYaml, List<PocContainerReport> containers) {
}
