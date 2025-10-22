UPDATE catalog.elements e
SET properties =
    properties - 'abacResource'
    || jsonb_build_object(
        'abacParameters',
        jsonb_build_object(
                'resourceString', e.properties->'abacResource',
                'resourceType', 'CHAIN',
                'operation', 'ALL',
                'resourceDataType', 'String'
        )
    )
WHERE e.type = 'http-trigger'
  AND e.properties->>'accessControlType' = 'ABAC'
  AND e.properties->'abacParameters' is NULL;
