def count = exchange.getProperty('finallyCount') ?: 0
exchange.setProperty('finallyCount', count + 1)

if (exchange.getProperty('finallyError') == true) {
    throw new Exception('finallyError')
}

exchange.getMessage().setHeader('X-Finally-Step', true)
