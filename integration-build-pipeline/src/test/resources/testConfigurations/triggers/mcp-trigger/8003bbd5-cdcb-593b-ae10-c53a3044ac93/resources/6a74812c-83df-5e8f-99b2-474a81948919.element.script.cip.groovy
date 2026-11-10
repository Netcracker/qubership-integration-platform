def arguments = new LinkedHashMap(exchange.getMessage().getBody(Map))
exchange.setProperty('snapshotMcpArguments', arguments)
exchange.setProperty('snapshotMcpScriptReached', true)
if (arguments.mode == 'fail') {
    throw new IllegalStateException('MCP snapshot failure')
}
if (arguments.mode == 'no-message') {
    throw new IllegalStateException()
}
def result = arguments.mode == 'wrong-output' ? [unexpected: 7] : [received: arguments, marker: 'structured']
exchange.getMessage().setBody(result)
