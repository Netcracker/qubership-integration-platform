package org.qubership.integration.platform.engine.camel.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GrpcSenderPostProcessorTest {

    private static final String GRPC_SERVICE_NAME = "customer.profile.v1.CustomerProfileService";
    private static final String GRPC_METHOD_NAME = "GetCustomerProfile";

    private GrpcSenderPostProcessor processor;

    @Mock
    JsonFormat.Printer grpcPrinter;
    @Mock
    Exchange exchange;

    private ObjectMapper objectMapper = ObjectMappers.getObjectMapper();

    @BeforeEach
    void setUp() {
        exchange = MockExchanges.defaultExchange();
        objectMapper = ObjectMappers.getObjectMapper();
        processor = new GrpcSenderPostProcessor(grpcPrinter, objectMapper);
    }


    @Test
    void shouldConvertSingleGrpcResponseToJsonAndCleanupProperties() throws Exception {
        Message response = mock(Message.class);

        exchange.getMessage().setBody(response);
        exchange.setProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, GRPC_SERVICE_NAME);
        exchange.setProperty(CamelConstants.Properties.GRPC_METHOD_NAME, GRPC_METHOD_NAME);

        when(grpcPrinter.print(response)).thenReturn("{\"customerId\":\"C-100500\"}");

        processor.process(exchange);

        assertEquals(
                "{\"customerId\":\"C-100500\"}",
                exchange.getMessage().getBody(String.class)
        );
        assertEquals(
                MediaType.APPLICATION_JSON,
                exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE)
        );
        assertNull(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME));
        assertNull(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME));
    }

    @Test
    void shouldConvertGrpcResponsesListToJsonArrayAndCleanupProperties() throws Exception {
        Message firstResponse = mock(Message.class);
        Message secondResponse = mock(Message.class);

        exchange.getMessage().setBody(List.of(firstResponse, secondResponse));
        exchange.setProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, GRPC_SERVICE_NAME);
        exchange.setProperty(CamelConstants.Properties.GRPC_METHOD_NAME, GRPC_METHOD_NAME);

        when(grpcPrinter.print(firstResponse)).thenReturn("{\"customerId\":\"C-100500\"}");
        when(grpcPrinter.print(secondResponse)).thenReturn("{\"customerId\":\"C-100501\"}");

        processor.process(exchange);

        JsonNode expected = objectMapper.readTree("""
                [
                  {"customerId":"C-100500"},
                  {"customerId":"C-100501"}
                ]
                """);
        JsonNode actual = objectMapper.readTree(exchange.getMessage().getBody(String.class));

        assertEquals(expected, actual);
        assertEquals(
                MediaType.APPLICATION_JSON,
                exchange.getMessage().getHeader(HttpHeaders.CONTENT_TYPE)
        );
        assertNull(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME));
        assertNull(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME));
    }
}
