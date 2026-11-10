import groovy.json.JsonOutput
import org.apache.camel.impl.DefaultCamelContext
import org.apache.camel.support.DefaultExchange

def chain = new com.fasterxml.jackson.databind.ObjectMapper().readValue(new File(args[0]), Map)
def scripts = chain.elements.findAll { element -> element.type == 'script' }
assert scripts.size() == 1 : [scriptCount: scripts.size()]

new DefaultCamelContext().withCloseable { context ->
    def exchange = new DefaultExchange(context)
    context.resolveLanguage('groovy').createExpression(scripts.first().properties.script).evaluate(exchange, Object)
    def actual = exchange.message.body
    assert String.isInstance(actual) && !actual.isBlank() : [body: actual]
    println JsonOutput.toJson([
        verdict: 'PASS',
        actual: actual,
        scriptBody: [verdict: 'PASS', scope: 'Persisted Groovy executed on a Camel Exchange'],
        httpResponse: [verdict: 'NOT_RUN', reason: 'The script harness does not execute HTTP response binding'],
        scope: 'Script body only; not a deployed engine run'
    ])
}
