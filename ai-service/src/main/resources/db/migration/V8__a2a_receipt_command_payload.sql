-- Preserve the resolved approval before dispatch so text replies can resume after the gate moves.
ALTER TABLE a2a_caller_message_receipts
    ADD COLUMN IF NOT EXISTS command_payload_json TEXT;
