package org.qubership.integration.platform.engine.configuration.camel;

import org.apache.camel.Exchange;
import org.apache.camel.component.http.HttpActivityListener;
import org.apache.camel.component.http.HttpComponent;
import org.apache.camel.spi.ComponentCustomizer;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts the exchange within reach of endpoint mocking.
 *
 * <p>An HTTP client is built once, when a chain is deployed, so the interceptor and the route planner that
 * mock an outbound call are the same for every run of that chain. What tells a test case run from a live one
 * is the exchange, and camel-http only files it into the client context when the endpoint carries an activity
 * listener. The listener below does nothing else; it exists so that the context carries the exchange.
 */
@Configuration
@ConditionalOnProperty(value = "qip.testing.enabled", havingValue = "true")
public class TestingHttpComponentConfiguration {

    private static final HttpActivityListener NOOP_LISTENER = new NoopActivityListener();

    @Bean
    public ComponentCustomizer testingHttpComponentCustomizer() {
        return ComponentCustomizer.builder(HttpComponent.class)
                .build(component -> component.setHttpActivityListener(NOOP_LISTENER));
    }

    private static class NoopActivityListener implements HttpActivityListener {
        @Override
        public void onRequestSubmitted(
                Object source, Exchange exchange, HttpHost host, HttpRequest request, HttpEntity entity) {
            // Nothing to observe. The listener earns its place by existing: camel-http files the exchange
            // into the client context only for an endpoint that carries one.
        }

        @Override
        public void onResponseReceived(
                Object source, Exchange exchange, HttpHost host, HttpResponse response, HttpEntity entity,
                long elapsed) {
            // Nothing to observe, for the reason above.
        }
    }
}
