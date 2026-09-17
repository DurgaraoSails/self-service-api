package com.sails.ai.selfserviceapi.asset.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VectorLiteralTest {

    @Test
    @DisplayName("renders pgvector's bracketed, comma-separated form with no spaces")
    void rendersPgvectorForm() {
        assertThat(VectorLiteral.of(new float[]{0.1f, -0.25f, 0.5f})).isEqualTo("[0.1,-0.25,0.5]");
    }

    @Test
    @DisplayName("a single-element vector has no separator")
    void rendersSingleElement() {
        assertThat(VectorLiteral.of(new float[]{1.5f})).isEqualTo("[1.5]");
    }

    @Test
    @DisplayName("an empty vector renders as empty brackets rather than throwing")
    void rendersEmpty() {
        assertThat(VectorLiteral.of(new float[]{})).isEqualTo("[]");
    }

    /**
     * The write path (indexer) and read path (query embedding) both go through this method, so
     * identical floats must produce identical text — otherwise a stored vector and a query vector
     * built from the same numbers could differ and skew cosine distance.
     */
    @Test
    @DisplayName("is deterministic for the same input")
    void isDeterministic() {
        float[] vector = {0.017f, 0.9993f, -0.4f};
        assertThat(VectorLiteral.of(vector)).isEqualTo(VectorLiteral.of(vector.clone()));
    }
}
