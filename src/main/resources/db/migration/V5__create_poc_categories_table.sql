CREATE TABLE poc_categories (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name  VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO poc_categories (name) VALUES
    ('Healthcare'),
    ('RAG'),
    ('Process Assistant'),
    ('Accelerators');
