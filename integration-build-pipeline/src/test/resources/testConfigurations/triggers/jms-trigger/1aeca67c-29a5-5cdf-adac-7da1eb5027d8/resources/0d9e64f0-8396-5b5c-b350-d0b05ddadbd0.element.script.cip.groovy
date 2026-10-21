if (exchange.getMessage().getHeader('snapshotFail', Boolean.class) == Boolean.TRUE
        && exchange.getMessage().getHeader('JMSRedelivered', Boolean.class) != Boolean.TRUE) {
    throw new IllegalStateException('JMS snapshot failure')
}
exchange.getMessage().setBody('Triggered')
