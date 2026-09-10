package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetEvent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {
}
