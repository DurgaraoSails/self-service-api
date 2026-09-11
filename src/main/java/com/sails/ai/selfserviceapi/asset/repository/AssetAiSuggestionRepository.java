package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetAiSuggestion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetAiSuggestionRepository extends JpaRepository<AssetAiSuggestion, UUID> {

    Optional<AssetAiSuggestion> findFirstByRevisionIdOrderByCreatedAtDesc(UUID revisionId);

    /** Reuse gate for the AI Suggestion Contract's dedupe rule: one successful result per
     * (revision_id, input_checksum, provider, model, schema_version) is reusable. */
    Optional<AssetAiSuggestion> findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc(
            UUID revisionId, String inputChecksum, String provider, String model, String schemaVersion, String status);
}
