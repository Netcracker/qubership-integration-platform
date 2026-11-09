import groovy.json.JsonOutput

exchange.getMessage().setHeader('Content-Type', 'application/json')
exchange.getMessage().setBody(JsonOutput.toJson([
    value: exchange.getProperty('value'),
    query: exchange.getMessage().getHeader('CamelHttpQuery'),
    headers: exchange.getMessage().getHeader('X-Snapshot-Repeated'),
    tags: exchange.getMessage().getHeader('tag'),
    body: exchange.getMessage().getBody(String)
]))
