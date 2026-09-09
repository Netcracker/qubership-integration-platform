def exceptionType = exchange.getProperty("exceptionType", String.class);

if (exceptionType != "") {

  if (exceptionType == 'arithmetic'){
    def a = 1 / 0;
  }
  else if (exceptionType == 'index'){
    def arr = [1,2] as int[];
    def i = arr[8];
  }
  else{
    throw new Exception("Other Exception");
  }
}
