exchange.getMessage().setBody('Main branch')
exchange.getMessage().setHeader('X-Branch-Name', 'Main')
exchange.setProperty('branchValue', 'Main')
