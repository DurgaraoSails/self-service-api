CREATE TABLE demo_session_statuses (
                                       code        TEXT PRIMARY KEY,
                                       description TEXT NOT NULL
);

INSERT INTO demo_session_statuses (code, description) VALUES
                                                          ('requested', 'Session requested, token issued'),
                                                          ('confirmed', 'Session actively in use'),
                                                          ('completed', 'Session ended normally'),
                                                          ('cancelled', 'Session revoked or expired without use');

CREATE TABLE demo_sessions (
                               id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                               poc_id       BIGINT NOT NULL REFERENCES pocs(id),
                               user_id      VARCHAR(36) NOT NULL REFERENCES users(id),
                               status       TEXT NOT NULL DEFAULT 'requested' REFERENCES demo_session_statuses(code),
                               access_token TEXT,
                               expires_at   TIMESTAMPTZ,
                               created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_demo_sessions_poc_id ON demo_sessions(poc_id);
CREATE INDEX idx_demo_sessions_user_id ON demo_sessions(user_id);