update catalog.elements e
set
    properties = e.properties || '{"handleChainFailureAction": "default", "chainFailureHandlerContainer": {}}'::jsonb
from
    catalog.chains c
where
    (c.id = e.chain_id)
    and (e.type = 'http-trigger')
    and (not (e.properties ? 'handleChainFailureAction'));

update catalog.elements e
set
    properties = e.properties - 'before'
    from
    catalog.chains c
where
    (c.id = e.chain_id)
    and (e.type = 'service-call')
    and (e.properties ? 'before')
    and (e.properties->'before' <@ '{}'::jsonb);

update catalog.elements e
set
    properties = e.properties - 'asyncValidationSchema'
    from
    catalog.chains c
where
    (c.id = e.chain_id)
    and (e.type = 'async-api-trigger')
    and (e.properties ? 'asyncValidationSchema')
    and (e.properties->'asyncValidationSchema' <@ '{}'::jsonb);

update catalog.elements e
set
    properties = jsonb_set(e.properties, '{priority}', to_jsonb((e.properties->>'priority')::int))
    from
    catalog.chains c
where
    (c.id = e.chain_id)
    and (e.type = 'if')
    and (e.properties ? 'priority')
    and (jsonb_typeof(e.properties->'priority') = 'string');
