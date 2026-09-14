package com.sails.ai.selfserviceapi.onboarding.generate;

/**
 * A Dockerfile the draft model proposed for a container that declared none. Advisory only — unlike
 * a generated {@code poc.yaml}, nothing validates a Dockerfile's content, so it is presented for
 * copy-and-review rather than committed automatically. Keeping delivery copy-paste, not an
 * auto-PR, is the mitigation for repository content (untrusted input) influencing what this writes.
 *
 * @param path    where it belongs in the repository, e.g. {@code apps/api/Dockerfile}.
 * @param content the proposed Dockerfile text.
 * @param reason  why the model proposed this shape — the stack it detected and from what evidence.
 */
public record GeneratedDockerfile(String path, String content, String reason) {
}
