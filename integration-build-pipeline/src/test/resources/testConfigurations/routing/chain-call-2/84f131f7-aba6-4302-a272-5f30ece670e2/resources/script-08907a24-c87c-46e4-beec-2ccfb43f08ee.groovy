if (exchange.getProperty('failChild') == true) {
    throw new IllegalStateException('Child chain failed')
}

exchange.getMessage().setBody('Subchain called')
exchange.getMessage().setHeader('X-Test-Request', 'child-header')
exchange.setProperty('testProperty', 'child-property')
