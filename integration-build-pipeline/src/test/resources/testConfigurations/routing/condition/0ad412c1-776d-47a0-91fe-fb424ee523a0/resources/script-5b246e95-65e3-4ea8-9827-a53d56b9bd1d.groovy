exchange.setProperty("elseExecutions", exchange.getProperty("elseExecutions", Integer.class) + 1);
exchange.getMessage().setBody("Else");
