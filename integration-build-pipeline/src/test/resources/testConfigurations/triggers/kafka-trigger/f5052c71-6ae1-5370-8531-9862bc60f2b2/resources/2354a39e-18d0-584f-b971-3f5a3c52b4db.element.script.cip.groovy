def describe = { value -> value == null ? 'null' : Base64.encoder.encodeToString(value) }
exchange.message.body = 'bytes:' + describe(exchange.message.body) +
        '|key:' + describe(exchange.message.getHeader('kafka.KEY'))
