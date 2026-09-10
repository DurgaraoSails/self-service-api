package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetAiSuggestion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetAiSuggestionRepository extends JpaRepository<AssetAiSuggestion, UUID> {
}
