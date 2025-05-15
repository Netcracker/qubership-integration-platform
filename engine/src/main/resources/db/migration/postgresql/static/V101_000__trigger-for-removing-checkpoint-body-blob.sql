CREATE OR REPLACE FUNCTION remove_large_object_func() RETURNS TRIGGER AS
$remove_large_object_func$
DECLARE
    oid_column TEXT := tg_argv[0];
    oid_value  OID;
BEGIN
    EXECUTE FORMAT('SELECT ($1).%I', oid_column) INTO oid_value USING old;
    PERFORM lo_unlink(oid_value);
    RETURN old;
END;
$remove_large_object_func$ LANGUAGE plpgsql;

CREATE TRIGGER remove_checkpoint_body_large_object
    AFTER DELETE
    ON engine.checkpoints
    FOR EACH ROW
EXECUTE FUNCTION remove_large_object_func('body');
