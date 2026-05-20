UPDATE catalog.elements
SET properties = jsonb_set(properties, '{accessControlType}', '"NONE"')
WHERE type = 'http-trigger'
  AND properties->>'accessControlType' = 'RBAC'
  AND properties ->>'roles' IS NULL
  OR (
    jsonb_typeof(properties->'roles') = 'array'
    AND jsonb_array_length(properties->'roles') = 0
  );
