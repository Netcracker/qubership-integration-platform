def count = exchange.getProperty('finallyCount') ?: 0
exchange.setProperty('finallyCount', count + 1)
