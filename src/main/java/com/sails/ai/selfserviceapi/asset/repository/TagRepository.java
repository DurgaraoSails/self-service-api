package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, Long> {
}
