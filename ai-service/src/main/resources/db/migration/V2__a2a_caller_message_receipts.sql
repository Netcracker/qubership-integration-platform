-- Caller-scoped Message receipts for lost-initial-response recovery (plan idempotency).
-- Additive only; keeps V1 (task_id, message_id) receipts for continuation dedupe.

CREATE TABLE a2a_caller_message_receipts (
    tenant_id VARCHAR(255) NOT NULL,
    subject_id VARCHAR(255) NOT NULL,
    message_id VARCHAR(255) NOT NULL,
    task_id VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_a2a_caller_message_receipts PRIMARY KEY (tenant_id, subject_id, message_id)
);

CREATE INDEX idx_a2a_caller_message_receipts_task_id
    ON a2a_caller_message_receipts (task_id);
