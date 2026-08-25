INSERT INTO pocs (name, description, app_url, details, guide_steps) VALUES
    ('Contract Agent', 'Review & generate contracts.', NULL, NULL, '{}'),
    ('Knowledge Assistant', 'Answer questions & summaries.', NULL, NULL, '{}'),
    ('Customer Support Agent', 'Handle support inquiries.', NULL, NULL, '{}'),
    ('Multi-Agent Workflow', 'Coordinate multiple agents.', NULL, NULL, '{}'),
    (
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
