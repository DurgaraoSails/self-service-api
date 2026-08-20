CREATE TABLE otp_verifications (
    id             VARCHAR(36) PRIMARY KEY,
    user_id        VARCHAR(36) NOT NULL REFERENCES users(id),
    otp_hash       VARCHAR(64) NOT NULL,
    generated_at   TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    resend_count   INTEGER NOT NULL DEFAULT 0,
    verified_at    TIMESTAMPTZ,
    status         VARCHAR(20) NOT NULL DEFAULT 'PENDING'
);

CREATE INDEX idx_otp_verifications_user_generated ON otp_verifications (user_id, generated_at);
CREATE INDEX idx_otp_verifications_user_status ON otp_verifications (user_id, status);
