exchange.getMessage().setHeader('snapshotSubscriber', 'first')
if (exchange.getMessage().getHeader('snapshotFailSubscriber', String.class) == 'first'
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean.class) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
