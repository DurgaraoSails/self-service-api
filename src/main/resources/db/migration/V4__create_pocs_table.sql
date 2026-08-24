CREATE TABLE pocs (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name         VARCHAR(200) NOT NULL,
    description  VARCHAR(2000) NOT NULL,
    icon_url     VARCHAR(500),
    app_url      VARCHAR(500),
    github_url   VARCHAR(500),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
