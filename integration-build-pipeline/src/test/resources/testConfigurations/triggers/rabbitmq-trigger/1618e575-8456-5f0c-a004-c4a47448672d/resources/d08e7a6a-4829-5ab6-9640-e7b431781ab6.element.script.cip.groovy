if (exchange.getMessage().getHeader('snapshotFail', Boolean) == Boolean.TRUE) {
    throw new IllegalStateException('RabbitMQ snapshot failure')
}
exchange.getMessage().setBody('Triggered')
