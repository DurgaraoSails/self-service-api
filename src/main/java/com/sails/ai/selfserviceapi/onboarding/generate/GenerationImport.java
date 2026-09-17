package com.sails.ai.selfserviceapi.onboarding.generate;

import java.util.List;

/**
 * What was read from one cloudbuild.yaml — maps 1:1 onto {@code PocGenerationImport} in the
 * OpenAPI spec. Built from a {@code cloudbuild.CloudBuildImport} by flattening its containers'
 * settings into the {@code (container, key, value)} rows a portal can render as a table, never a
 * secret's value, only names.
 */
public record GenerationImport(String sourcePath, List<String> services, List<Setting> settings,
                                List<String> secretNames, List<Unsupported> unsupported) {

    public record Setting(String container, String key, String value) {
    }

    public record Unsupported(String setting, String reason) {
    }
}
