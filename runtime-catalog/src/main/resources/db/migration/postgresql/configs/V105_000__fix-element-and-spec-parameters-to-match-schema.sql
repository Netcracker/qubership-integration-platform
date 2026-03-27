update catalog.elements e
set
    properties = e.properties - 'maxLoopIteration'
    from
    catalog.chains c
where
    (c.id = e.chain_id)
  and (e.type = 'loop-2')
  and (e.properties ? 'maxLoopIteration')
  and (e.properties->>'maxLoopIteration' = '');

update catalog.elements e
set
    properties = e.properties - 'reconnectBackoffMaxMs'
    from
    catalog.chains c
where
    (c.id = e.chain_id)
  and (e.type = 'kafka-trigger-2')
  and (e.properties ? 'reconnectBackoffMaxMs')
  and (e.properties->> 'reconnectBackoffMaxMs' = '');

update catalog.operations o
set
    name = concat('-', o.id, o.method, o.path)
where
    (o.name is null)
    or (o.name = '');
