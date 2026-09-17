-- Append-only. No updated_at on purpose.
CREATE TABLE role_change_audit (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id       VARCHAR(36) NOT NULL REFERENCES users(id),
    target_user_id      VARCHAR(36) NOT NULL REFERENCES users(id),
    before_roles        TEXT[] NOT NULL,
    after_roles         TEXT[] NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_role_change_audit_target_user_id ON role_change_audit (target_user_id);
