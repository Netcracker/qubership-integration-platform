BEGIN;

ALTER TABLE catalog.elements
    DISABLE TRIGGER update_chain_modified_param;

UPDATE catalog.elements e
SET properties =
        jsonb_set(
                e.properties - 'asyncValidationSchema',
                '{after,0,schema}',
                to_jsonb(e.properties ->> 'asyncValidationSchema'),
                true
        )
WHERE e.type = 'async-api-trigger'
  AND e.properties ? 'asyncValidationSchema'
  AND jsonb_typeof(e.properties -> 'after') = 'array'
  AND jsonb_array_length(e.properties -> 'after') = 1;

UPDATE catalog.elements e
SET properties = jsonb_set(
        e.properties,
        '{afterValidation}',
        (SELECT jsonb_agg(
                        CASE
                            WHEN elem ? 'scheme'
                                THEN elem - 'scheme' || jsonb_build_object('schema', elem -> 'scheme')
                            ELSE elem
                            END
                )
         FROM jsonb_array_elements(e.properties -> 'afterValidation') elem),
        false
                 )
WHERE e.type = 'service-call'
  AND jsonb_typeof(e.properties -> 'afterValidation') = 'array'
  AND jsonb_array_length(e.properties -> 'afterValidation') <> 0;

ALTER TABLE catalog.elements
    ENABLE TRIGGER update_chain_modified_param;

COMMIT;
