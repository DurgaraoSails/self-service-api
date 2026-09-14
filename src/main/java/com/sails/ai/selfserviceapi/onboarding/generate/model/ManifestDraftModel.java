package com.sails.ai.selfserviceapi.onboarding.generate.model;

/**
 * Turns a prompt into JSON conforming to a schema. Knows nothing about manifests, poc.yaml, or
 * GitHub — {@code onboarding.generate} is the only package that gives this shape meaning.
 *
 * <p>One adapter per provider (see {@code OllamaDraftModel}, {@code VertexDraftModel}), each built
 * on {@code RestClient} the same way every other integration in this codebase is — no AI framework
 * dependency, because the port is one method wide and a framework would buy nothing here that isn't
 * already this cheap to write by hand.
 */
public interface ManifestDraftModel {

    /** Matched against {@code poc-generator.provider} to select which adapter runs. */
    String name();

    /** A cheap reachability probe — never throws, so the generation pipeline can degrade gracefully. */
    boolean isAvailable();

    /** Raw JSON text conforming to {@link ModelRequest#jsonSchema()}. Throws on failure; never returns null. */
    String draft(ModelRequest request);
}
