-- Additive command reconciliation metadata for crash recovery (prompt 10 Finding 3).
-- Do not edit V1-V5. Stores the safe command kind refinement and precondition revision
-- used to decide whether an Incomplete receipt must re-dispatch.

ALTER TABLE a2a_caller_message_receipts
    ADD COLUMN IF NOT EXISTS precondition_revision BIGINT,
    ADD COLUMN IF NOT EXISTS command_descriptor VARCHAR(128);

COMMENT ON COLUMN a2a_caller_message_receipts.precondition_revision IS
    'Facade snapshot revision observed at claim time for continue/approve reconciliation';
COMMENT ON COLUMN a2a_caller_message_receipts.command_descriptor IS
    'Safe command kind for reconciliation: initial-clarify, continue-clarify, approve, blocked-recovery, auto-implement';
