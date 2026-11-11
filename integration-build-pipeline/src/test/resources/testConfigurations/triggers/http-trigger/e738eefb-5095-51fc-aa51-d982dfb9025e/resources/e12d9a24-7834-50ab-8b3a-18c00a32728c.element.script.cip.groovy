exchange.getMessage().setHeader('snapshotMainExecuted', true)
if (exchange.getMessage().getHeader('X-Snapshot-Fail') == 'true') {
    throw new IllegalStateException('HTTP snapshot downstream failure')
}
exchange.getMessage().setBody('Completed')
