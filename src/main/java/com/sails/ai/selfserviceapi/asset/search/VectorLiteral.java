package com.sails.ai.selfserviceapi.asset.search;

/**
 * Renders an embedding as a pgvector text literal ({@code [0.01,0.02,...]}), the form both the
 * indexer's insert and the search query's {@code cast(? as vector)} bind expect.
 *
 * <p>Shared deliberately: the write path and the read path must produce byte-identical formatting,
 * or a stored vector and a query vector built from the same floats could differ. Kept out of any
 * provider class since it is a property of the database's wire format, not of Voyage AI.
 */
public final class VectorLiteral {

    private VectorLiteral() {
    }

    public static String of(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(vector[i]);
        }
        return builder.append(']').toString();
    }
}
