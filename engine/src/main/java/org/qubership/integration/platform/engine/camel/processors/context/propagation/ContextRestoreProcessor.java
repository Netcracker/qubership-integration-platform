package org.qubership.integration.platform.engine.camel.processors.context.propagation;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.http.HttpHeaders;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeContextPropagation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;

import static java.util.Objects.nonNull;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties.REQUEST_CONTEXT_PROPAGATION_SNAPSHOT;

@Slf4j
@Component("contextRestoreProcessor")
@ConditionalOnMissingBean(name = "contextRestoreProcessor")
public class ContextRestoreProcessor implements Processor {
    private final CamelExchangeContextPropagation camelExchangeContextPropagation;

    @Autowired
    public ContextRestoreProcessor(CamelExchangeContextPropagation camelExchangeContextPropagation) {
        this.camelExchangeContextPropagation = camelExchangeContextPropagation;
    }

    @Override
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> exchangeHeaders = exchange.getMessage().getHeaders();
        Map<String, Object> contextSnapshot = (Map<String, Object>) exchange.getProperty(REQUEST_CONTEXT_PROPAGATION_SNAPSHOT);
        if (contextSnapshot != null) {
            camelExchangeContextPropagation.activateContextSnapshot(contextSnapshot);

            Object authorization = exchangeHeaders.get(HttpHeaders.AUTHORIZATION);
            camelExchangeContextPropagation.removeContextHeaders(exchangeHeaders);
            if (nonNull(authorization) && !exchangeHeaders.containsKey(HttpHeaders.AUTHORIZATION)) {
                exchangeHeaders.put(HttpHeaders.AUTHORIZATION, authorization);
            }

            restoreAdditionalHeaders(exchange);
        } else {
            log.warn("Failed to restore context, " + REQUEST_CONTEXT_PROPAGATION_SNAPSHOT
                    + " property not present in exchange, it must not be changed or removed manually");
        }
    }

    protected void restoreAdditionalHeaders(Exchange exchange) {
        // do nothing
    }
}
