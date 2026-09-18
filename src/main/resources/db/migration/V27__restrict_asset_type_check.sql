-- Asset Hub's asset type list is trimmed to POC, BLOG, and HACKATHON_IDEA — AI_USE_CASE, ARTICLE,
-- and DOCUMENT are no longer offered. Reclassify any existing row still using a removed type as
-- BLOG (the closest remaining category for written, non-hosted content) before the stricter CHECK
-- constraint below would otherwise reject it outright.
UPDATE assets SET asset_type = 'BLOG' WHERE asset_type IN ('AI_USE_CASE', 'ARTICLE', 'DOCUMENT');

ALTER TABLE assets DROP CONSTRAINT ck_assets_asset_type;

ALTER TABLE assets ADD CONSTRAINT ck_assets_asset_type
    CHECK (asset_type IN ('POC', 'BLOG', 'HACKATHON_IDEA'));
