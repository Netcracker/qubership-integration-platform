exchange.getMessage().setHeader('snapshotSubscriber', 'second')
if (exchange.getMessage().getHeader('snapshotFailSubscriber', String.class) == 'second'
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean.class) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
