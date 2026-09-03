package com.sails.ai.selfserviceapi.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PocAudienceTest {

    @Test
    void buildsAndReadsBackTheSameSlug() {
        String audience = PocAudience.forSlug("contract-agent");

        assertThat(PocAudience.slugOf(List.of(audience))).contains("contract-agent");
    }

    @Test
    void aUserTokenHasNoPocAudience() {
        assertThat(PocAudience.isPocToken(List.of())).isFalse();
        assertThat(PocAudience.isPocToken(null)).isFalse();
        assertThat(PocAudience.slugOf(List.of("self-service-portal"))).isEmpty();
    }

    /**
     * A token naming two POCs is not something this API mints, so it resolves to no POC rather
     * than to whichever happens to come first — picking one would silently grant access to it.
     */
    @Test
    void refusesToChooseBetweenTwoPocAudiences() {
        List<String> audiences = List.of(PocAudience.forSlug("a"), PocAudience.forSlug("b"));

        assertThat(PocAudience.isPocToken(audiences)).isTrue();
        assertThat(PocAudience.slugOf(audiences)).isEmpty();
    }
}
