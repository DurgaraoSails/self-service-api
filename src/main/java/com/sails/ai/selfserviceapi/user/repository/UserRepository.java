package com.sails.ai.selfserviceapi.user.repository;

import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.entity.UserStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {

    Optional<User> findByEmail(String email);

    Page<User> findByCreatedAtBetween(Instant from, Instant to, Pageable pageable);

    Page<User> findByStatusAndTrialEndDateBetween(UserStatus status, Instant from, Instant to, Pageable pageable);

    long countByStatusAndTrialEndDateBetween(UserStatus status, Instant from, Instant to);
}
