def message = exchange.getMessage();
message.setBody(message.getBody(String.class) + '|reuse');
message.setHeader('X-Reuse-Trace', message.getHeader('X-Reuse-Trace', String.class) + '|reuse');
exchange.setProperty('reuseTrace', exchange.getProperty('reuseTrace', String.class) + '|reuse');
exchange.setProperty('reuseCount', exchange.getProperty('reuseCount', Integer.class) + 1);
