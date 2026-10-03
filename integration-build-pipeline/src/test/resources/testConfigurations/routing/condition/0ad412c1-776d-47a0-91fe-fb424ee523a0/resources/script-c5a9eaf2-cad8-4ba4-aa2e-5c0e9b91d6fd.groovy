exchange.setProperty('ifExecutions', exchange.getProperty('ifExecutions', Integer) + 1)
exchange.getMessage().setBody('If')
