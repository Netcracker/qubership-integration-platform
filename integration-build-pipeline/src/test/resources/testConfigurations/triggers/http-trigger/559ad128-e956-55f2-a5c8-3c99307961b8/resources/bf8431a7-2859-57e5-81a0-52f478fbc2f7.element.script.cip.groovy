exchange.getMessage().setHeader('snapshotFailureHandler', true)
exchange.getMessage().setHeader('CamelHttpResponseCode', 503)
exchange.getMessage().setBody('Handled chain failure')
