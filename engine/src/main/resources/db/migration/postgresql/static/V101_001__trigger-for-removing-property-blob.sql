CREATE TRIGGER remove_property_large_object
    AFTER DELETE
    ON engine.properties
    FOR EACH ROW
EXECUTE FUNCTION remove_large_object_func('value');
