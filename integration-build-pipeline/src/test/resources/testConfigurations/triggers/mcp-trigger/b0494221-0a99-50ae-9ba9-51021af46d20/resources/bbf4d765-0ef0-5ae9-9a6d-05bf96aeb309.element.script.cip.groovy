def arguments = new LinkedHashMap(exchange.getMessage().getBody(Map))
exchange.setProperty('snapshotMcpArguments', arguments)
exchange.setProperty('snapshotMcpScriptReached', true)
if (arguments.mode == 'fail') {
    throw new IllegalStateException('MCP snapshot failure')
}
if (arguments.mode == 'no-message') {
    throw new IllegalStateException()
}
exchange.getMessage().setBody('Triggered')
