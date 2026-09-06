-- Ensure task-scoped message receipts exist before V3 adds command_fingerprint.
-- Additive only. Heals schemas where V1 is recorded but a2a_message_receipts was never created.

CREATE TABLE IF NOT EXISTS a2a_message_receipts (
    task_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_a2a_message_receipts PRIMARY KEY (task_id, message_id)
);

CREATE INDEX IF NOT EXISTS idx_a2a_message_receipts_task_id ON a2a_message_receipts (task_id);
