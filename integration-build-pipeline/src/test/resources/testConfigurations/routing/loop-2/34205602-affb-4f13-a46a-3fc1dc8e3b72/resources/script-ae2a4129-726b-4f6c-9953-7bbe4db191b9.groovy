def message = exchange.getMessage()
def index = exchange.getProperty("iterationIndex", Integer.class)
exchange.getProperty("visitedIterations", List.class).add(index)
if (index == exchange.getProperty("failAtIteration", Integer.class)) {
    throw new IllegalStateException("Loop failed at iteration index " + index)
}
message.setBody(message.getBody(String.class) + ":" + index)
