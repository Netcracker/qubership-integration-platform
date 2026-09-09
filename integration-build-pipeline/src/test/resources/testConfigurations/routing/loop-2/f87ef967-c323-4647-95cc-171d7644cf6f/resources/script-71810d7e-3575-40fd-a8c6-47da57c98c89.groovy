exchange.setProperty('increment', exchange.getProperty('increment',Integer.class) +1);
exchange.setProperty('counter', exchange.getProperty('counter',Integer.class) -1);
