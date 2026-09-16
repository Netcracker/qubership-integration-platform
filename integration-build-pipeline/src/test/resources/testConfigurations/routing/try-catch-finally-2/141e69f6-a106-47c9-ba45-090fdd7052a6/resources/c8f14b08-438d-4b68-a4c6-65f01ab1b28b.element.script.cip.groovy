exchange.setProperty('atCatch', true)

if(exchange.getProperty('catchError') == true){
  throw new Exception('catchError')
}

exchange.getMessage().setBody("ArithmeticException occurred")
