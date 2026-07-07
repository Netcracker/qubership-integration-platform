package org.qubership.integration.platform.engine.camel.processors.context.propagation;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.hc.core5.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.OVERRIDE_CONTEXT_PARAMS;

@Slf4j
@Component("contextPropagationProcessor")
public class ContextPropagationProcessor implements Processor {
    private final CamelExchangeContextPropagation camelExchangeContextPropagation;

    @Autowired
    public ContextPropagationProcessor(
        CamelExchangeContextPropagation camelExchangeContextPropagation
    ) {
        this.camelExchangeContextPropagation = camelExchangeContextPropagation;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> overrideMap = exchange.getProperty(OVERRIDE_CONTEXT_PARAMS, Map.class); // convert JSON string to map
            if (nonNull(overrideMap)) {
                camelExchangeContextPropagation.overrideHeadersForCurrentContext(overrideMap);
            }

            Map<String, Object> exchangeHeaders = exchange.getMessage().getHeaders();
            Object authorizationHeader = exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION);
            camelExchangeContextPropagation.removeContextHeaders(exchangeHeaders);
            camelExchangeContextPropagation.propagateExchangeHeaders(exchangeHeaders);
            if (nonNull(authorizationHeader)) {
                exchangeHeaders.put(HttpHeaders.AUTHORIZATION, authorizationHeader);
            }
        } catch (Exception e) {
            log.error("Failed to propagate context before sending message", e);
        }
    }
}
