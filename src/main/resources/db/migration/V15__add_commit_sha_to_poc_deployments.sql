-- The commit main's HEAD pointed at when this deployment's tag was created — without this,
-- check-updates (T10.8) has nothing to compare a freshly-fetched HEAD SHA against.
ALTER TABLE poc_deployments ADD COLUMN commit_sha TEXT;
