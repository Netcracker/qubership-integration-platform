if (exchange.message.getHeader('snapshotFail') == 'true') {
    throw new IllegalStateException('Kafka snapshot failure')
}
exchange.message.body = 'Triggered'
