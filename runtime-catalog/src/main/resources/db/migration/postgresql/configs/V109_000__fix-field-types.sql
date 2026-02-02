-- Changing a type of 'priorityNumber' field from string to integer for 'when' and 'catch' elements.
update catalog.elements e
set
    properties = jsonb_set(e.properties, '{priorityNumber}', to_jsonb((e.properties->>'priorityNumber')::int))
    from
    catalog.chains c
where
    (c.id = e.chain_id)
  and (e.type = 'when') or (e.type = 'catch')
  and (e.properties ? 'priorityNumber')
  and (jsonb_typeof(e.properties->'priorityNumber') = 'string');


-- Changing the type of 'priority' field from string to integer for 'catch-2' element.
update catalog.elements e
set
    properties = jsonb_set(e.properties, '{priority}', to_jsonb((e.properties->>'priority')::int))
    from
    catalog.chains c
where
    (c.id = e.chain_id)
  and (e.type = 'catch-2')
  and (e.properties ? 'priority')
  and (jsonb_typeof(e.properties->'priority') = 'string');


-- Removing 'priorityNumber' property from 'if' element.
update catalog.elements e
set
    properties = e.properties - 'priorityNumber'
    from
    catalog.chains c
where
    (c.id = e.chain_id)
  and (e.type = 'if')
  and (e.properties ? 'priorityNumber');
