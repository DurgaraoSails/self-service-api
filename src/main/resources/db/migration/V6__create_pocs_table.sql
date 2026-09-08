CREATE TABLE pocs (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                    VARCHAR(200) NOT NULL,
    description             VARCHAR(2000) NOT NULL,
    icon_url                VARCHAR(500),
    app_url                 VARCHAR(500),
    github_url              VARCHAR(500),
    owner                   VARCHAR(200),

    -- References poc_categories(name), not its id: the admin dropdown and every other read of
    -- this column already deal in the category name, and poc_categories.name is already unique.
    category                VARCHAR(100) REFERENCES poc_categories(name),

    technologies            TEXT[] NOT NULL DEFAULT '{}',
    demo_type               VARCHAR(50),

    -- Visible on the dashboard vs. withdrawn without deleting. Named status originally; renamed
    -- here to disambiguate from a deployment's own status.
    visibility_status       VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',

    details                 TEXT,
    guide_steps             TEXT[] NOT NULL DEFAULT '{}',

    deleted_at              TIMESTAMPTZ,

    -- Addressable name for the deploy target — a Cloud Run service name and an Artifact Registry
    -- image path both need a stable, URL-safe identifier; neither can be derived from a numeric
    -- id, and deriving it from `name` breaks the moment a POC is renamed.
    slug                    TEXT UNIQUE,

    -- Upstream tracking: answers "is main ahead of what's deployed?". Written by the deploy
    -- pipeline (which holds the GitHub credential), never by self-service-api itself.
    latest_main_commit_sha  TEXT,
    latest_main_checked_at  TIMESTAMPTZ,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT pocs_slug_format_check
        CHECK (slug IS NULL OR slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')

    -- active_version_id (FK to poc_versions.id) is added by V7, once poc_versions exists —
    -- the two tables reference each other, so one of the two FKs has to come from the other side.
);
