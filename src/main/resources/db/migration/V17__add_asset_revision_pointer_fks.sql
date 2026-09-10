-- assets.approved_revision_id / working_revision_id can only be added now that asset_revisions
-- (and its (id, asset_id) unique pair) exists. The composite FK is the invariant itself: neither
-- pointer can ever reference a revision belonging to a different asset.

ALTER TABLE assets
    ADD CONSTRAINT fk_assets_approved_revision
        FOREIGN KEY (approved_revision_id, id) REFERENCES asset_revisions (id, asset_id);

ALTER TABLE assets
    ADD CONSTRAINT fk_assets_working_revision
        FOREIGN KEY (working_revision_id, id) REFERENCES asset_revisions (id, asset_id);
