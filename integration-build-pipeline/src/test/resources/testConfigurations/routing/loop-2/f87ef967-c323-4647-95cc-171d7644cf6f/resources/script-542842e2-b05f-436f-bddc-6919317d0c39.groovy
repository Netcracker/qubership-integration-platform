if (exchange.getProperty('counter') == null) {
    exchange.setProperty('counter', 10);
}
exchange.setProperty('increment', 0);
