-- Columns without a known real value yet (icon_url, github_url, version, owner, category,
-- technologies, container_image, demo_type) are left NULL/empty here — update once known,
-- there's no other seed path for them (not exposed via the POC admin API yet).
INSERT INTO pocs (
    slug, name, description, icon_url, app_url, github_url, version, owner, category,
    technologies, container_image, demo_type, status, details, guide_steps
) VALUES
    ('contract-agent', 'Contract Agent', 'Review & generate contracts.', NULL, NULL, NULL, NULL, NULL, NULL, '{}', NULL, NULL, 'ACTIVE', NULL, '{}'),
    ('knowledge-assistant', 'Knowledge Assistant', 'Answer questions & summaries.', NULL, NULL, NULL, NULL, NULL, NULL, '{}', NULL, NULL, 'ACTIVE', NULL, '{}'),
    ('customer-support-agent', 'Customer Support Agent', 'Handle support inquiries.', NULL, NULL, NULL, NULL, NULL, NULL, '{}', NULL, NULL, 'ACTIVE', NULL, '{}'),
    ('multi-agent-workflow', 'Multi-Agent Workflow', 'Coordinate multiple agents.', NULL, NULL, NULL, NULL, NULL, NULL, '{}', NULL, NULL, 'ACTIVE', NULL, '{}'),
    (
        'sails-process-assistant',
        'Sails Process Assistant',
        'Guided assistant for Sails processes.',
        NULL,
        'https://sails-process-assistant-421602618878.us-central1.run.app/',
        NULL,
        NULL,
        NULL,
        NULL,
        '{}',
        NULL,
        NULL,
        'ACTIVE',
        'An AI-guided assistant that walks Sails teams through internal processes step by step, answering questions and surfacing the right resources along the way.',
        ARRAY[
            'Click Launch to open the assistant in your session.',
            'Describe the process or task you need help with in plain language.',
            'Follow the assistant''s step-by-step guidance, providing details when asked.',
            'Use the links and resources it surfaces to complete the process.'
        ]
    );
