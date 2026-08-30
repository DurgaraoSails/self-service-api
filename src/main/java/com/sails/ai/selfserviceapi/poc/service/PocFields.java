package com.sails.ai.selfserviceapi.poc.service;

import java.util.List;

/**
 * The mutable, caller-supplied fields of a POC — shared by create and update so the two don't
 * drift, and to avoid a same-type positional-parameter list long enough to risk a silent
 * transposition bug (this grew past that point once details/guideSteps joined the original five).
 */
public record PocFields(
        String name,
        String description,
        String iconUrl,
        String appUrl,
        String githubUrl,
        String version,
        String owner,
        String category,
        List<String> technologies,
        String containerImage,
        String demoType,
        String status,
        String embedMode,
        String details,
        List<String> guideSteps
) {
}
