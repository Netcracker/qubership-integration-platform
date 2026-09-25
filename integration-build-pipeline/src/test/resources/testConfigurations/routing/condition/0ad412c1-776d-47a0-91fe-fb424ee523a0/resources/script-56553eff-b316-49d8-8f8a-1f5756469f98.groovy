exchange.setProperty('if10Executions', exchange.getProperty('if10Executions', Integer) + 1)
exchange.getMessage().setBody('If-10')
