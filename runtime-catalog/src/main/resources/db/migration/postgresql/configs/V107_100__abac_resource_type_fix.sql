UPDATE catalog.elements e
SET properties = properties || jsonb_set(properties, '{abacParameters,resourceType}', '"${abacResourceType}"', FALSE)
WHERE e.type = 'http-trigger'
  AND e.properties ->> 'accessControlType' = 'ABAC'
  AND e.properties -> 'abacParameters' is not NULL
  AND e.properties #>> '{abacParameters,resourceType}' = 'CHAIN';
