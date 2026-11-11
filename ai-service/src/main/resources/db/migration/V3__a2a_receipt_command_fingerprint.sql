-- Command fingerprint on Message receipts for idempotency conflict detection (plan).
-- Additive only; existing receipt rows (if any) get an empty fingerprint and stay readable.

ALTER TABLE a2a_caller_message_receipts
    ADD COLUMN command_fingerprint VARCHAR(128) NOT NULL DEFAULT '';

ALTER TABLE a2a_message_receipts
    ADD COLUMN command_fingerprint VARCHAR(128) NOT NULL DEFAULT '';
