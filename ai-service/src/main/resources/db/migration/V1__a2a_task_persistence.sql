-- A2A Task persistence (prompt 02). Additive only; no down migration.
-- Logical database name in deployment: ai_a2a (wired by prompt 07).

CREATE TABLE a2a_tasks (
    task_id VARCHAR(255) NOT NULL,
    context_id VARCHAR(255),
    conversation_id VARCHAR(255) NOT NULL,
    state VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    tenant_id VARCHAR(255),
    subject_id VARCHAR(255),
    public_snapshot TEXT NOT NULL,
    message_history TEXT NOT NULL,
    artifact_metadata TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    CONSTRAINT pk_a2a_tasks PRIMARY KEY (task_id)
);

CREATE INDEX idx_a2a_tasks_conversation_id ON a2a_tasks (conversation_id);
CREATE INDEX idx_a2a_tasks_finalized_at ON a2a_tasks (finalized_at);

CREATE TABLE a2a_message_receipts (
    task_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_a2a_message_receipts PRIMARY KEY (task_id, message_id)
);

CREATE INDEX idx_a2a_message_receipts_task_id ON a2a_message_receipts (task_id);
