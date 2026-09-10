CREATE TABLE asset_events (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                UUID REFERENCES assets(id) ON DELETE CASCADE,
    user_id                 VARCHAR(36) REFERENCES users(id),
    event_type              VARCHAR(20) NOT NULL,

    -- Correlates a detail/source/launch event back to the search that led to it. No raw query
    -- text is ever stored anywhere in this table.
    search_session_id       UUID,

    occurred_at             TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_asset_events_event_type
        CHECK (event_type IN ('SEARCH', 'DETAIL_VIEW', 'SOURCE_OPEN', 'POC_LAUNCH'))
);

CREATE INDEX idx_asset_events_asset_id ON asset_events (asset_id);
CREATE INDEX idx_asset_events_occurred_at ON asset_events (occurred_at);
CREATE INDEX idx_asset_events_search_session_id
    ON asset_events (search_session_id) WHERE search_session_id IS NOT NULL;
