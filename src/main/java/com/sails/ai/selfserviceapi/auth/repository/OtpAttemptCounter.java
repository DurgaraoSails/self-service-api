package com.sails.ai.selfserviceapi.auth.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// Atomic UPDATE...RETURNING — avoids the read-then-write race a plain JPA save() would have under concurrent verify attempts.
@Repository
public class OtpAttemptCounter {

    private final JdbcTemplate jdbcTemplate;

    public OtpAttemptCounter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<OtpAttemptSnapshot> incrementAttempt(String otpVerificationId) {
        List<OtpAttemptSnapshot> rows = jdbcTemplate.query(
                """
                UPDATE otp_verifications
                SET attempt_count = attempt_count + 1
                WHERE id = ? AND status = 'PENDING'
                RETURNING attempt_count, otp_hash
                """,
                (rs, rowNum) -> new OtpAttemptSnapshot(
                        rs.getInt("attempt_count"),
                        rs.getString("otp_hash")
                ),
                otpVerificationId
        );
        return rows.stream().findFirst();
    }
}
