CREATE TABLE pocs (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug             TEXT UNIQUE NOT NULL,
    name             VARCHAR(200) NOT NULL,
    description      VARCHAR(2000) NOT NULL,
    icon_url         VARCHAR(500),
    app_url          VARCHAR(500),
    github_url       VARCHAR(500),
    version          VARCHAR(50),
    owner            VARCHAR(200),
    category         VARCHAR(100),
    technologies     TEXT[] NOT NULL DEFAULT '{}',
    container_image  VARCHAR(500),
    demo_type        VARCHAR(50),
    status           VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
