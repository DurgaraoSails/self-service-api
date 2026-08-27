package com.sails.ai.selfserviceapi.auth.repository;

import com.sails.ai.selfserviceapi.auth.entity.RegistrationVerificationToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegistrationVerificationTokenRepository extends JpaRepository<RegistrationVerificationToken, UUID> {

    Optional<RegistrationVerificationToken> findByTokenHash(String tokenHash);

    List<RegistrationVerificationToken> findAllByUserIdAndUsedAtIsNull(String userId);
}
