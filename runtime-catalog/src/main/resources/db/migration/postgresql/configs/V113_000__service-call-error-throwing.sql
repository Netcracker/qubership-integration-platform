-- Applies the rules of V109ChainImportFileMigration to stored Service Calls, snapshot copies included.
BEGIN;

ALTER TABLE catalog.elements
    DISABLE TRIGGER update_chain_modified_param;

UPDATE catalog.elements e
SET properties =
        CASE
            WHEN jsonb_typeof(e.properties -> 'after') IS NULL OR jsonb_typeof(e.properties -> 'after') = 'null'
                THEN jsonb_set(e.properties - 'errorThrowing', '{after}', '[]'::jsonb, true)
            WHEN jsonb_typeof(e.properties -> 'after') = 'array'
                THEN jsonb_set(
                    e.properties - 'errorThrowing',
                    '{after}',
                    COALESCE(
                            (SELECT jsonb_agg(
                                            CASE
                                                WHEN jsonb_typeof(i.item) = 'object'
                                                    AND i.item ->> 'code' = 'Default'
                                                    AND NOT EXISTS (SELECT 1
                                                                    FROM jsonb_array_elements(e.properties -> 'after') d
                                                                    WHERE d ->> 'code' = 'default')
                                                    THEN i.item || '{"code": "default"}'::jsonb
                                                WHEN jsonb_typeof(i.item) = 'object'
                                                    AND i.item ->> 'code' ~ '^[1-5]xx$'
                                                    AND i.item -> 'wildcard' IS DISTINCT FROM 'true'::jsonb
                                                    THEN i.item || jsonb_build_object(
                                                        'code', left(i.item ->> 'code', 1) || '00..' || left(i.item ->> 'code', 1) || '99',
                                                        'wildcard', true)
                                                ELSE i.item
                                                END
                                            ORDER BY i.ord)
                             FROM jsonb_array_elements(e.properties -> 'after') WITH ORDINALITY i(item, ord)),
                            '[]'::jsonb),
                    true)
            ELSE e.properties - 'errorThrowing'
            END
WHERE e.type = 'service-call';

ALTER TABLE catalog.elements
    ENABLE TRIGGER update_chain_modified_param;

COMMIT;
