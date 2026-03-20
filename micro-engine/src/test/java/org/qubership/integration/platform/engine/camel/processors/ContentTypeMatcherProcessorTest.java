package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContentTypeMatcherProcessorTest {

    private final ContentTypeMatcherProcessor processor = new ContentTypeMatcherProcessor();

    @Test
    void shouldMatchWhenExpectedContentTypeIsAny() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(CamelConstants.Properties.EXPECTED_CONTENT_TYPE, "*/*");
        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json;charset=UTF-8");

        processor.process(exchange);

        assertTrue(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenActualContentTypeIsAbsent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenContentTypesHaveDifferentLength() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "application/json"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenMimeTypesDifferentButTotalLengthEqual() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "text/css;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "text/xml;charset=UTF-8"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldMatchWhenSecondaryDirectivesHaveDifferentOrder() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;model=individual;version=v1;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "application/json;charset=UTF-8;version=v1;model=individual"
        );

        processor.process(exchange);

        assertTrue(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenSecondaryDirectivesDifferent() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;model=individual;version=v1;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "application/json;charset=UTF-8;version=v1;model=company"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenMimeTypesDifferentButLengthsEqual() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "application/xml;charset=UTF-8"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }

    @Test
    void shouldNotMatchWhenSecondaryDirectivesDifferentButLengthsEqual() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();

        exchange.setProperty(
                CamelConstants.Properties.EXPECTED_CONTENT_TYPE,
                "application/json;version=v1;charset=UTF-8"
        );
        exchange.getMessage().setHeader(
                Exchange.CONTENT_TYPE,
                "application/json;version=v2;charset=UTF-8"
        );

        processor.process(exchange);

        assertFalse(exchange.getProperty(
                CamelConstants.Properties.MATCHED_CONTENT_TYPES,
                Boolean.class
        ));
    }
}
