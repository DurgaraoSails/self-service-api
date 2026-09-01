package com.sails.ai.selfserviceapi.poc.repository;

import com.sails.ai.selfserviceapi.poc.entity.PocCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PocCategoryRepository extends JpaRepository<PocCategory, Long> {

    List<PocCategory> findAllByOrderByNameAsc();
}
