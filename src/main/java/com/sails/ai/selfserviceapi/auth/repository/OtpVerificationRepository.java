package com.sails.ai.selfserviceapi.auth.repository;

import com.sails.ai.selfserviceapi.auth.entity.OtpStatus;
import com.sails.ai.selfserviceapi.auth.entity.OtpVerification;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtpVerificationRepository extends JpaRepository<OtpVerification, String> {

    Optional<OtpVerification> findFirstByUserIdAndStatusOrderByGeneratedAtDesc(String userId, OtpStatus status);

    long countByUserIdAndGeneratedAtAfter(String userId, Instant since);
}
