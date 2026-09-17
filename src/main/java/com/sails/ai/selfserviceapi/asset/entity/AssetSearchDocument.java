package com.sails.ai.selfserviceapi.asset.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * The {@code embedding} column and pgvector extension are Phase 3 only — see this table's
 * migration ({@code V21__create_asset_search_documents_table.sql}) for why. Nothing here maps a
 * vector column yet.
 *
 * <p>{@code search_vector} is {@code NOT NULL} with no database default and is read-only through
 * this entity (see the field's javadoc) — the indexer that creates/updates a row must do so with a
 * native SQL statement that sets {@code search_vector} itself. A plain {@code repository.save(new
 * AssetSearchDocument())} will fail the NOT NULL constraint.
 */
@Entity
@Table(name = "asset_search_documents")
@Getter
@Setter
public class AssetSearchDocument {

    @Id
    @Column(name = "asset_id", nullable = false, updatable = false)
    private UUID assetId;

    @Column(name = "revision_id", nullable = false)
    private UUID revisionId;

    @Column(name = "search_text", nullable = false)
    private String searchText;

    /**
     * Read-only through JPA on purpose: Postgres won't accept a plain text bind parameter for a
     * {@code tsvector} column, and building the weighted vector needs {@code to_tsvector}/
     * {@code setweight} run server-side anyway. The indexer writes this column with a native
     * {@code UPDATE ... SET search_vector = setweight(to_tsvector(...), 'A') || ...} query, not
     * through this entity.
     */
    @Column(name = "search_vector", columnDefinition = "tsvector", nullable = false, insertable = false, updatable = false)
    private String searchVector;

    @Column(name = "embedding_provider", length = 100)
    private String embeddingProvider;

    @Column(name = "embedding_model", length = 100)
    private String embeddingModel;

    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;

    @Column(name = "embedding_checksum", length = 64)
    private String embeddingChecksum;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;
}
