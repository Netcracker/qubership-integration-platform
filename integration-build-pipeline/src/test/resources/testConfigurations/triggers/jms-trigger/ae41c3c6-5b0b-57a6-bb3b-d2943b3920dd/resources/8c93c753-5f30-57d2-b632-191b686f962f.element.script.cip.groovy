exchange.getMessage().setHeader('snapshotSubscriber', 'second')
if (exchange.getMessage().getHeader('snapshotFailSubscriber', String) == 'second'
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
