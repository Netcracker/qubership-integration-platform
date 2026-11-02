def describe = { value -> value == null ? 'null' : value.class.name + ':' + value.toString() }
exchange.message.body = describe(exchange.message.body) + '|key:' + describe(exchange.message.getHeader('kafka.KEY'))
