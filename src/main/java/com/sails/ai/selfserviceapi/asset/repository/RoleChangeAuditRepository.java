package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.RoleChangeAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoleChangeAuditRepository extends JpaRepository<RoleChangeAudit, UUID> {
}
