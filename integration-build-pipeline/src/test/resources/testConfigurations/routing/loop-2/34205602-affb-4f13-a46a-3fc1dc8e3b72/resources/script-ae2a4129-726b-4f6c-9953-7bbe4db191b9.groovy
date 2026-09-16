def message = exchange.getMessage()
def index = exchange.getProperty('iterationIndex', Integer)
exchange.getProperty('visitedIterations', List).add(index)
if (index == exchange.getProperty('failAtIteration', Integer)) {
    throw new IllegalStateException('Loop failed at iteration index ' + index)
}
message.setBody(message.getBody(String.class) + ':' + index)
