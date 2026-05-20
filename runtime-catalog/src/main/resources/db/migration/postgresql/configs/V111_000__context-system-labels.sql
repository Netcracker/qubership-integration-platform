CREATE TABLE context_system_labels
(
    name      VARCHAR(255) NOT NULL,
    context_system_id  VARCHAR(255) NOT NULL
        CONSTRAINT fk_context_system_labels_context_system
            REFERENCES context_system
            ON DELETE CASCADE,
    id        VARCHAR(255) NOT NULL
            PRIMARY KEY,
    technical BOOLEAN DEFAULT FALSE,
    CONSTRAINT uk_context_system_labels
        UNIQUE (name, context_system_id, technical)
);

CREATE INDEX idx_context_system_labels_context_system_id
    ON context_system_labels (context_system_id);
