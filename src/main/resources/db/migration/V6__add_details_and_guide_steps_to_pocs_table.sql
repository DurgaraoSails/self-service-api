ALTER TABLE pocs
    ADD COLUMN details     TEXT,
    ADD COLUMN guide_steps TEXT[] NOT NULL DEFAULT '{}';
