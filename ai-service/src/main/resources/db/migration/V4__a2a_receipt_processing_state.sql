-- Durable receipt processing state for resumable A2A dispatch (review remediation R4).
-- Additive only. Existing rows are treated as COMPLETED (they finished under the prior model).

ALTER TABLE a2a_caller_message_receipts
    ADD COLUMN IF NOT EXISTS processing_state VARCHAR(32) NOT NULL DEFAULT 'COMPLETED',
    ADD COLUMN IF NOT EXISTS fingerprint_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    ADD COLUMN IF NOT EXISTS command_kind VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_task_revision BIGINT,
    ADD COLUMN IF NOT EXISTS response_task_revision BIGINT,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE a2a_caller_message_receipts
    DROP CONSTRAINT IF EXISTS chk_a2a_caller_receipt_processing_state;

ALTER TABLE a2a_caller_message_receipts
    ADD CONSTRAINT chk_a2a_caller_receipt_processing_state
    CHECK (processing_state IN ('CLAIMED', 'DISPATCHING', 'COMPLETED'));
