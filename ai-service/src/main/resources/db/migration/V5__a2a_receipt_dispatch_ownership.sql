-- Exclusive dispatch ownership for A2A caller receipts (prompt 09 finding 3).
-- Additive only. Do not edit V1-V4.

ALTER TABLE a2a_caller_message_receipts
    ADD COLUMN IF NOT EXISTS dispatch_owner_token UUID,
    ADD COLUMN IF NOT EXISTS dispatch_lease_until TIMESTAMPTZ;
