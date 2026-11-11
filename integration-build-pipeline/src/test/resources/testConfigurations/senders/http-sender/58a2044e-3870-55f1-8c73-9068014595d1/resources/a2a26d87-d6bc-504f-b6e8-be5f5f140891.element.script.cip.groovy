def caught = exchange.getProperty('CamelExceptionCaught')
exchange.setProperty('atCatch', true)
exchange.setProperty('caughtStatus', caught.statusCode)
exchange.setProperty('caughtBody', caught.responseBody)
