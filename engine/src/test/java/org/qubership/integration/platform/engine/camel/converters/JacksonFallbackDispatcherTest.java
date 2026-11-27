package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.Exchange;
import org.apache.camel.converter.stream.InputStreamCache;
import org.apache.camel.language.jq.JqExpression;
import org.apache.camel.language.jq.JqFunctions;
import org.apache.camel.spring.SpringCamelContext;
import org.apache.camel.support.builder.ExpressionBuilder;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.configuration.MapperConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonFallbackDispatcherTest {
    public static class Payload {
        public int z = 1;
        public int a = 2;
    }

    @Test
    void usesJackson2InsideJqAndJackson3Outside() throws Exception {
        try (GenericApplicationContext spring = new GenericApplicationContext()) {
            spring.refresh();
            SpringCamelContext context = new SpringCamelContext(spring);
            context.getGlobalOptions().put("CamelJacksonEnableTypeConverter", "true");
            context.getGlobalOptions().put("CamelJacksonTypeConverterToPojo", "true");
            JacksonFallbackDispatcher.register(context);
            context.start();
            try {
                Exchange exchange = context.getEndpoint("direct:test").createExchange();
                exchange.getMessage().setBody("{}");
                exchange.getMessage().setHeader("payload", new Payload());
                JqExpression expression = new JqExpression("header(\"payload\")");
                expression.init(context);
                assertEquals("{\"z\":1,\"a\":2}", ((JsonNode) expression.evaluate(exchange, Object.class)).asText());
                exchange.setProperty("payload", new Payload());
                exchange.setVariable("payload", new Payload());
                for (String source : List.of("property", "variable")) {
                    JqExpression function = new JqExpression(source + "(\"payload\")");
                    function.init(context);
                    assertEquals("{\"z\":1,\"a\":2}", ((JsonNode) function.evaluate(exchange, Object.class)).asText());
                }
                assertEquals("{\"a\":2,\"z\":1}", context.getTypeConverter().convertTo(String.class, exchange, new Payload()));
                assertNull(JqFunctions.EXCHANGE_LOCAL.get());

                for (var source : List.of(ExpressionBuilder.headerExpression("payload"),
                        ExpressionBuilder.exchangePropertyExpression("payload"), ExpressionBuilder.variableExpression("payload"))) {
                    JqExpression sourced = new JqExpression(".z");
                    sourced.setSource(source);
                    sourced.init(context);
                    assertEquals(1, ((JsonNode) sourced.evaluate(exchange, Object.class)).asInt());
                }
                exchange.getMessage().setBody(Map.of("items", List.of(1, 2)));
                JqExpression multiple = new JqExpression(".items[]");
                multiple.setResultType(Integer.class);
                multiple.init(context);
                assertEquals(List.of(1, 2), multiple.evaluate(exchange, Object.class));

                exchange.getMessage().setBody("{\"z\":7,\"a\":3}");
                JqExpression objectResult = new JqExpression(".");
                objectResult.setResultType(Payload.class);
                objectResult.init(context);
                Payload result = (Payload) objectResult.evaluate(exchange, Object.class);
                assertEquals(7, result.z);
                assertEquals(3, result.a);

                InputStreamCache cache = new InputStreamCache("{\"z\":1}".getBytes(StandardCharsets.UTF_8));
                exchange.getMessage().setBody(cache);
                JqExpression predicate = new JqExpression(".z == 1");
                predicate.init(context);
                assertTrue(predicate.matches(exchange));
                assertEquals(0, cache.position());
                JqExpression error = new JqExpression("error(\"failure\")");
                error.init(context);
                assertThrows(RuntimeException.class, () -> error.evaluate(exchange, Object.class));
                assertNull(JqFunctions.EXCHANGE_LOCAL.get());
            } finally {
                context.stop();
            }
        }
    }

    @Test
    void retainsCamelMapperLookupWithEngineConfiguration() throws Exception {
        try (AnnotationConfigApplicationContext spring = new AnnotationConfigApplicationContext(MapperConfiguration.class)) {
            SpringCamelContext context = new SpringCamelContext(spring);
            context.getGlobalOptions().put("CamelJacksonEnableTypeConverter", "true");
            context.getGlobalOptions().put("CamelJacksonTypeConverterToPojo", "true");
            JacksonFallbackDispatcher.register(context);
            context.start();
            try {
                assertEquals(2, context.getRegistry().findByType(tools.jackson.databind.ObjectMapper.class).size());
                Exchange exchange = context.getEndpoint("direct:test").createExchange();
                String actual = context.getTypeConverter().convertTo(String.class, exchange, new Payload());
                String expected = new org.apache.camel.component.jackson3.converter.JacksonTypeConverters()
                        .convertTo(String.class, exchange, new Payload(), context.getTypeConverterRegistry());
                assertEquals(expected, actual);
            } finally {
                context.stop();
            }
        }
    }

}
