def message = exchange.getMessage()
def index = exchange.getProperty('CamelLoopIndex')
message.setBody(message.getBody(String) + ':' + index)
