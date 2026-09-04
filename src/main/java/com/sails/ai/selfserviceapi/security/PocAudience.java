package com.sails.ai.selfserviceapi.security;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The audience convention that separates the two classes of token this API issues, both signed by
 * the same key: a user's own access token, and a POC-scoped token minted by
 * {@code POST /pocs/{slug}/launch}.
 *
 * <p>The distinction is load-bearing rather than cosmetic. A POC-scoped token is handed to
 * JavaScript running on a POC's origin, so it must not be usable against the portal's endpoints —
 * and a user access token must not be usable against the POC-facing ones. Every check on either
 * side goes through this class so the two can never drift apart.
 */
public final class PocAudience {

    /** Namespaced so no plausible future audience value can collide with a POC's. */
    public static final String PREFIX = "poc:";

    private PocAudience() {
    }

    public static String forSlug(String slug) {
        return PREFIX + slug;
    }

    public static boolean isPocToken(Collection<String> audiences) {
        return audiences != null && audiences.stream().anyMatch(aud -> aud != null && aud.startsWith(PREFIX));
    }

    /**
     * The POC a token is scoped to. Empty unless exactly one POC audience is present: a token
     * naming two POCs is not something this API mints, so it is treated as unusable rather than
     * having one of them picked arbitrarily.
     */
    public static Optional<String> slugOf(Collection<String> audiences) {
        if (audiences == null) {
            return Optional.empty();
        }
        List<String> pocAudiences = audiences.stream()
                .filter(aud -> aud != null && aud.startsWith(PREFIX))
                .toList();
        return pocAudiences.size() == 1
                ? Optional.of(pocAudiences.getFirst().substring(PREFIX.length()))
                : Optional.empty();
    }
}
