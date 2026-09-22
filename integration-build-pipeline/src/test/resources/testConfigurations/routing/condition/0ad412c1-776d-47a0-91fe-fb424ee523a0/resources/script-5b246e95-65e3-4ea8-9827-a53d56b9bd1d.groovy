exchange.setProperty('elseExecutions', exchange.getProperty('elseExecutions', Integer) + 1)
exchange.getMessage().setBody('Else')
