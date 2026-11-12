exchange.getMessage().setHeader('Content-Type',
    exchange.getMessage().getHeader('X-Snapshot-Response-Content-Type', String) ?: 'application/json')
exchange.getMessage().setHeader('X-Snapshot-Response', 'visible')
