-- Documents a user uploads for a POC to work on. See docs/specs/file-management.md.
--
-- Scoped to a (user, POC) pair rather than a per-user library: a document uploaded for the
-- contract-review POC is not visible to any other POC the same user trials. That scoping is what
-- makes the purge boundary, the authorization rule and the object layout all the same shape.
CREATE TABLE user_files (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- No ON DELETE CASCADE, deliberately, and unlike activity_sessions. Cascading would let a
    -- deleted user take these rows with it while the GCS objects they name stayed behind — an
    -- orphaned object with no row is data the platform has lost track of and can no longer purge.
    -- Blocking the user delete until purge has run is the safe direction to fail in.
    user_id           VARCHAR(36) NOT NULL REFERENCES users (id),
    poc_id            BIGINT NOT NULL REFERENCES pocs (id),

    -- Full GCS object path: users/<userId>/pocs/<pocId>/<fileId>. User-first because purge is the
    -- dominant bulk operation and is scoped to a user, making it one prefix delete.
    object_name       TEXT NOT NULL UNIQUE,

    -- As uploaded, and metadata only — never part of object_name. A filename is frequently
    -- personal data in its own right ("Q3-layoffs-J-Smith.pdf"), and one embedded in an object
    -- path survives in bucket listings and access logs that outlive the object itself.
    original_filename TEXT NOT NULL,

    -- Client-declared. Stored for the download response, never trusted for validation — the
    -- upload path sniffs magic bytes instead.
    content_type      TEXT NOT NULL,
    size_bytes        BIGINT NOT NULL,

    uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE user_files
    ADD CONSTRAINT user_files_size_bytes_check CHECK (size_bytes >= 0);

-- The listing query and the per-pair file-count quota. Partial, so soft-deleted rows never
-- enter it — both callers filter on deleted_at IS NULL.
CREATE INDEX user_files_owner_idx
    ON user_files (user_id, poc_id)
    WHERE deleted_at IS NULL;

-- The purge sweep and the per-user total-bytes quota, both of which span every POC and must
-- still see soft-deleted rows: their objects are not gone from the bucket until purge runs.
CREATE INDEX user_files_user_idx ON user_files (user_id);
