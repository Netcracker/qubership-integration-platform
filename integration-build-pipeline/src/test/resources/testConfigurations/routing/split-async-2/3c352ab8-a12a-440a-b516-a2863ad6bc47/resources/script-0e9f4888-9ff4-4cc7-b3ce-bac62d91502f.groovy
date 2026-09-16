exchange.getMessage().setBody("Secondary branch")
exchange.setProperty('test', "Secondary asynch branch")

if (exchange.getProperty("failSecondary", Boolean.class) == true) {
    throw new IllegalStateException("Secondary async branch failed")
}
