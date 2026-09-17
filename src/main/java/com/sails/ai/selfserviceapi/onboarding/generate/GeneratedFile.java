package com.sails.ai.selfserviceapi.onboarding.generate;

/**
 * One file this generation run produced — maps 1:1 onto {@code PocGeneratedFile} in the OpenAPI
 * spec. {@code poc.yaml} is always {@link Kind#POC_YAML}; a Dockerfile lint finding or a template
 * choice determines the rest.
 *
 * @param container   which container this file belongs to, when it belongs to one specific
 *                    container. Null for poc.yaml itself.
 * @param needsReview true for anything not proven safe by a validator or a vetted template — a
 *                    model-drafted Dockerfile for an unrecognized stack, most notably. Never true
 *                    for poc.yaml, which always passed {@code ManifestValidator} before reaching here.
 */
public record GeneratedFile(String path, Kind kind, Action action, Source source, String container,
                             String content, String reason, boolean needsReview) {

    public enum Kind {
        POC_YAML, DOCKERFILE, DOCKERIGNORE, CONFIG
    }

    public enum Action {
        CREATE, REPLACE
    }

    public enum Source {
        TEMPLATE, MODEL, DERIVED
    }

    public static GeneratedFile pocYaml(String content, String reason) {
        return new GeneratedFile("poc.yaml", Kind.POC_YAML, Action.CREATE, Source.MODEL, null, content, reason, false);
    }
}
