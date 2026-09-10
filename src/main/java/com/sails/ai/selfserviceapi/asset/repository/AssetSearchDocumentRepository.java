package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetSearchDocument;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetSearchDocumentRepository extends JpaRepository<AssetSearchDocument, UUID> {
}
