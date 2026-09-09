package com.sails.ai.selfserviceapi.user.repository;

import com.sails.ai.selfserviceapi.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserRepository extends JpaRepository<User, String>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    @org.springframework.data.jpa.repository.Query("select u from User u where lower(trim(u.email)) = :email")
    java.util.List<User> findByNormalizedEmail(@org.springframework.data.repository.query.Param("email") String email);

    Optional<User> findByMicrosoftTenantIdAndMicrosoftObjectId(String tenantId, String objectId);
}
