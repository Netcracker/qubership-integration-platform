if (exchange.getMessage().getHeader('snapshotFail', Boolean) == Boolean.TRUE
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
