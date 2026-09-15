-- Sails Process Assistant is the one real, already-deployed POC (its own Cloud Run URL, not
-- managed by this app's deploy pipeline) — seeded so it's present in every environment without
-- being created by hand through the admin UI each time. No slug: it's reached directly via its
-- existing app_url, never through POST /pocs/{id}/deploy.
INSERT INTO pocs (name, description, app_url, details, guide_steps) VALUES (
    'Sails Process Assistant',
    'Guided assistant for Sails processes.',
    'https://sails-process-assistant-421602618878.us-central1.run.app/',
    'An AI-guided assistant that walks Sails teams through internal processes step by step, answering questions and surfacing the right resources along the way.',
    ARRAY[
        'Click Launch to open the assistant in your session.',
        'Describe the process or task you need help with in plain language.',
        'Follow the assistant''s step-by-step guidance, providing details when asked.',
        'Use the links and resources it surfaces to complete the process.'
    ]
);
