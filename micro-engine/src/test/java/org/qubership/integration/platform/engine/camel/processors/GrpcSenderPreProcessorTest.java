package org.qubership.integration.platform.engine.camel.processors;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import org.apache.camel.Exchange;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;
import org.qubership.integration.platform.engine.util.GrpcProcessorUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GrpcSenderPreProcessorTest {

    private final JsonFormat.Parser grpcParser = mock(JsonFormat.Parser.class);
    private final GrpcSenderPreProcessor processor = new GrpcSenderPreProcessor(grpcParser);

    @Test
    void shouldMergeJsonIntoBuilderAndSetBuiltMessageWhenBodyNotEmpty() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Message.Builder builder = mock(Message.Builder.class);
        Message builtMessage = mock(Message.class);

        FakeGrpcRequest.builder = builder;

        exchange.getMessage().setBody("{\"customerId\":\"C-100500\"}");
        when(builder.build()).thenReturn(builtMessage);

        try (MockedStatic<GrpcProcessorUtils> grpcProcessorUtils = mockStatic(GrpcProcessorUtils.class)) {
            grpcProcessorUtils.when(() -> GrpcProcessorUtils.getRequestClass(exchange))
                    .thenReturn(FakeGrpcRequest.class);

            processor.process(exchange);
        }

        verify(grpcParser).merge("{\"customerId\":\"C-100500\"}", builder);
        verify(builder).build();
        assertSame(builtMessage, exchange.getMessage().getBody());
    }

    @Test
    void shouldSkipMergeAndSetBuiltMessageWhenBodyEmpty() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Message.Builder builder = mock(Message.Builder.class);
        Message builtMessage = mock(Message.class);

        FakeGrpcRequest.builder = builder;

        exchange.getMessage().setBody("");
        when(builder.build()).thenReturn(builtMessage);

        try (MockedStatic<GrpcProcessorUtils> grpcProcessorUtils = mockStatic(GrpcProcessorUtils.class)) {
            grpcProcessorUtils.when(() -> GrpcProcessorUtils.getRequestClass(exchange))
                    .thenReturn(FakeGrpcRequest.class);

            processor.process(exchange);
        }

        verifyNoInteractions(grpcParser);
        verify(builder).build();
        assertSame(builtMessage, exchange.getMessage().getBody());
    }

    @Test
    void shouldSkipMergeAndSetBuiltMessageWhenBodyNull() throws Exception {
        Exchange exchange = MockExchanges.defaultExchange();
        Message.Builder builder = mock(Message.Builder.class);
        Message builtMessage = mock(Message.class);

        FakeGrpcRequest.builder = builder;

        when(builder.build()).thenReturn(builtMessage);

        try (MockedStatic<GrpcProcessorUtils> grpcProcessorUtils = mockStatic(GrpcProcessorUtils.class)) {
            grpcProcessorUtils.when(() -> GrpcProcessorUtils.getRequestClass(exchange))
                    .thenReturn(FakeGrpcRequest.class);

            processor.process(exchange);
        }

        verifyNoInteractions(grpcParser);
        verify(builder).build();
        assertSame(builtMessage, exchange.getMessage().getBody());
    }

    static class FakeGrpcRequest {
        static Message.Builder builder;

        public static Message.Builder newBuilder() {
            return builder;
        }
    }
}
