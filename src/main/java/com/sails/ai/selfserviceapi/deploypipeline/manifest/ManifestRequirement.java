package com.sails.ai.selfserviceapi.deploypipeline.manifest;

/**
 * One environment variable a container needs but whose value is not the manifest author's to give.
 *
 * <p>This is the split the whole secrets model rests on: the <em>author</em> knows what their code
 * reads, which is a fact about the code and belongs in the repo; the <em>value</em> is a fact about
 * the environment, changes without a commit, and for a secret must never be committed at all. So a
 * manifest declares the need and nothing else.
 *
 * <p>{@code secret} is the author's marker, not an operator's, because sensitivity is a property of
 * what the variable <em>is</em>. Whoever later fills in a form should not have to decide whether an
 * API key deserves Secret Manager.
 *
 * @param name   the environment variable name the container will receive
 * @param secret whether the value is resolved from Secret Manager at container start rather than
 *               passed as a plain environment variable
 */
public record ManifestRequirement(String name, boolean secret) {
}
