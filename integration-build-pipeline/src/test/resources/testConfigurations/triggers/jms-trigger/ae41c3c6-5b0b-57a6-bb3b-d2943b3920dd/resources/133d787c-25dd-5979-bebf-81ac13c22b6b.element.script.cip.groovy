exchange.getMessage().setHeader('snapshotSubscriber', 'first')
if (exchange.getMessage().getHeader('snapshotFailSubscriber', String) == 'first'
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
