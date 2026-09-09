-- The branch a POC's next version is cut from — the branch whose head commit gets tagged, built
-- and deployed. Supplied by the admin form alongside githubUrl, because the branch to release from
-- is a property of the repository, not of the platform: one POC releases from main, the next from
-- develop, and a platform-wide setting cannot be right for both.
--
-- NULL means "follow this repository's own default branch", whatever GitHub reports it to be. That
-- is the pre-existing behaviour, so every POC already in this table keeps deploying exactly as it
-- did before this column existed.
--
-- Shape is validated in the application (GitBranchNames), which knows git's ref-name rules and can
-- return a 400 naming the field. The constraint here only rules out the value that would be a
-- silent bug either way: a blank string, which is neither a branch nor NULL's "use the default".
ALTER TABLE pocs
    ADD COLUMN deploy_branch VARCHAR(255);

ALTER TABLE pocs
    ADD CONSTRAINT pocs_deploy_branch_not_blank
        CHECK (deploy_branch IS NULL OR btrim(deploy_branch) <> '');
