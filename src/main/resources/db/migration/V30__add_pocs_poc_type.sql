-- Whether a POC is a general internal tool (INTERNAL, the default — every POC predating this
-- column) or was built for a specific named client (CLIENT_SPECIFIC). Purely a catalog filter/label
-- for the dashboard's "POC type" filter, alongside the existing (freeform) category — no other part
-- of this platform's behavior depends on it.
ALTER TABLE pocs
    ADD COLUMN poc_type VARCHAR(20) NOT NULL DEFAULT 'INTERNAL';

ALTER TABLE pocs
    ADD CONSTRAINT pocs_poc_type_valid
        CHECK (poc_type IN ('INTERNAL', 'CLIENT_SPECIFIC'));
