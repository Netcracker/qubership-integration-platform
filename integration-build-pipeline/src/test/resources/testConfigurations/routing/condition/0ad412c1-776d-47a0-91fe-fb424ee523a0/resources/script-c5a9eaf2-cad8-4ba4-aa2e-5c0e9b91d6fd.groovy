exchange.setProperty("ifExecutions", exchange.getProperty("ifExecutions", Integer.class) + 1);
exchange.getMessage().setBody("If");
