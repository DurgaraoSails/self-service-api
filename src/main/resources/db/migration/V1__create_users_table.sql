CREATE TABLE users (
    id                 VARCHAR(36) PRIMARY KEY,
    first_name         VARCHAR(100) NOT NULL,
    last_name          VARCHAR(100) NOT NULL,
    company_name       VARCHAR(200) NOT NULL,
    job_title          VARCHAR(100),
    country            VARCHAR(100),
    email              VARCHAR(255) NOT NULL UNIQUE,
    display_name       VARCHAR(100),
    status             VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    roles              TEXT[] NOT NULL DEFAULT '{}',
    tenant_id          VARCHAR(100),
    trial_start_date   TIMESTAMPTZ,
    trial_end_date     TIMESTAMPTZ,
    last_login_date    TIMESTAMPTZ,
    first_login        BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified_at  TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_tenant_id ON users (tenant_id) WHERE tenant_id IS NOT NULL;
