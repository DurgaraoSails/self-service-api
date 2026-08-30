package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.DemoSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DemoSessionRepository extends JpaRepository<DemoSession, UUID> {
}
