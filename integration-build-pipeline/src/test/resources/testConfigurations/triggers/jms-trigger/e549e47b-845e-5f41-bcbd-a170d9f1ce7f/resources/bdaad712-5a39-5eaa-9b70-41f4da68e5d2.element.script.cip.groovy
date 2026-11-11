exchange.getMessage().setHeader('snapshotDuplicateHandler', true)
if (exchange.getMessage().getHeader('failDuplicateHandler', Boolean) == Boolean.TRUE) {
    throw new IllegalStateException('JMS duplicate handler failure')
}
exchange.getMessage().setBody('Duplicate handled')
