-- Whether a POC is put live by this platform's own build/deploy pipeline (AUTOMATIC, the existing
-- flow — githubUrl + slug feed DeploymentOrchestrator, which writes appUrl once a build succeeds)
-- or by an admin simply pasting an already-running Cloud Run URL into appUrl directly (SELF — for a
-- POC someone deployed by hand outside this platform, which still needs to be launchable). Every
-- existing row defaults to AUTOMATIC, which is exactly what it already does today — this column
-- only adds a second path, it never changes the first.
ALTER TABLE pocs
    ADD COLUMN deployment_mode VARCHAR(20) NOT NULL DEFAULT 'AUTOMATIC';

ALTER TABLE pocs
    ADD CONSTRAINT pocs_deployment_mode_valid
        CHECK (deployment_mode IN ('SELF', 'AUTOMATIC'));
