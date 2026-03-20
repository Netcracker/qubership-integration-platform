package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.http.HttpHeaders;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SecurityRestoreProcessorTest {

    private final SecurityRestoreProcessor processor = new SecurityRestoreProcessor();

    @Test
    void shouldRestoreAuthorizationHeaderWhenPreservedAuthorizationPresent() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.PRESERVED_AUTH_PROP,
                "Bearer eyJhbGciOiJIUzI1NiJ9.test.token"
        );

        processor.process(exchange);

        assertEquals(
                "Bearer eyJhbGciOiJIUzI1NiJ9.test.token",
                exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION)
        );
    }

    @Test
    void shouldRemoveAuthorizationHeaderWhenPreservedAuthorizationAbsent() {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.getMessage().setHeader(
                HttpHeaders.AUTHORIZATION,
                "Bearer old-token"
        );

        processor.process(exchange);

        assertNull(exchange.getMessage().getHeader(HttpHeaders.AUTHORIZATION));
    }
}
