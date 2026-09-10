package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetRevisionRepository extends JpaRepository<AssetRevision, UUID> {
}
