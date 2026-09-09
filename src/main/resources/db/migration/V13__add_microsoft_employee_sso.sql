ALTER TABLE users ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'EXTERNAL';
ALTER TABLE users ADD COLUMN microsoft_tenant_id VARCHAR(36);
ALTER TABLE users ADD COLUMN microsoft_object_id VARCHAR(36);
ALTER TABLE users ADD CONSTRAINT users_account_type_check CHECK (account_type IN ('EXTERNAL', 'INTERNAL'));
ALTER TABLE users ADD CONSTRAINT users_microsoft_identity_unique UNIQUE (microsoft_tenant_id, microsoft_object_id);
ALTER TABLE users ADD CONSTRAINT users_microsoft_identity_pair CHECK
    ((microsoft_tenant_id IS NULL) = (microsoft_object_id IS NULL));
CREATE INDEX idx_users_normalized_email ON users (lower(trim(email)));

CREATE TABLE microsoft_authorizations (
    state_hash VARCHAR(64) PRIMARY KEY,
    nonce VARCHAR(43) NOT NULL,
    code_challenge VARCHAR(43) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_microsoft_authorizations_expiry ON microsoft_authorizations (expires_at);
