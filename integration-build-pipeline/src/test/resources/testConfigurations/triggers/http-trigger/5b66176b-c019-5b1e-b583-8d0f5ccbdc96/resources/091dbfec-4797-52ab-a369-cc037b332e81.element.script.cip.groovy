exchange.getMessage().setHeader('snapshotDuplicateHandler', true)
exchange.getMessage().setHeader('CamelHttpResponseCode', 209)
exchange.getMessage().setBody('Handled duplicate')
