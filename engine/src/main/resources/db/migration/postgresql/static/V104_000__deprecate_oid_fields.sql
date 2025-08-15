DROP FUNCTION IF EXISTS remove_large_object_func CASCADE;

ALTER TABLE engine.checkpoints
    ADD body_bytea BYTEA;

ALTER TABLE engine.properties
    ADD value_bytea BYTEA;
