package org.qubership.integration.platform.engine.camel.converters;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.language.jq.JqFunctions;
import org.apache.camel.spi.TypeConverterRegistry;
import org.apache.camel.support.SimpleTypeConverter;

public final class JacksonFallbackDispatcher {
    private JacksonFallbackDispatcher() {
    }

    public static void register(CamelContext context) {
        TypeConverterRegistry registry = context.getTypeConverterRegistry();
        var jackson2 = new org.apache.camel.component.jackson.converter.JacksonTypeConverters();
        var jackson3 = new org.apache.camel.component.jackson3.converter.JacksonTypeConverters();
        registry.addFallbackTypeConverter(new SimpleTypeConverter(false, (type, exchange, value) -> {
            Exchange jqExchange = JqFunctions.EXCHANGE_LOCAL.get();
            if (jqExchange != null || JsonNode.class.isAssignableFrom(type) || value instanceof JsonNode) {
                return jackson2.convertTo(type, exchange != null ? exchange : jqExchange, value, registry);
            }
            return jackson3.convertTo(type, exchange, value, registry);
        }), false);
    }
}
