-- deployment_jobs (V16) was a hand-off queue for an external deploy pipeline that was never built
-- that way — InProcessDeploymentTrigger/PipelineRunner report status by direct in-process call
-- instead, and nothing in this codebase has ever written or read this table. Dropping it rather
-- than leaving it as a vestige a future change might mistakenly wire against.
DROP TABLE IF EXISTS deployment_jobs;
