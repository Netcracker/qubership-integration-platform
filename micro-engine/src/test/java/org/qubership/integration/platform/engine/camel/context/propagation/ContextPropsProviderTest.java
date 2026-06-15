package org.qubership.integration.platform.engine.camel.context.propagation;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.contexts.SerializableDataContext;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ContextPropsProviderTest {

    private final ContextPropsProvider provider = new ContextPropsProvider();

    @Test
    void shouldReturnEmptyHeadersWhenContextManagerHasNoContexts() {
        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getAll).thenReturn(Collections.emptyList());

            Set<String> headers = provider.getDownstreamHeaders();

            assertTrue(headers.isEmpty());
        }
    }

    @Test
    void shouldReturnCaseInsensitiveHeadersWhenSerializableContextsHaveData() {
        SerializableDataContext firstContext = serializableContext(Map.of(
            "X-Request-Id", "07219d13-23c0-4fbe-be83-f8f30df9a6c8",
            "X-Version", "v1"
        ));
        SerializableDataContext secondContext = serializableContext(Map.of(
            "Business-Request-Id", "c772b439-c904-4fa8-8b79-0b7f54163400"
        ));

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getAll).thenReturn(List.of(firstContext, secondContext));

            Set<String> headers = provider.getDownstreamHeaders();

            assertTrue(headers.contains("X-Request-Id"));
            assertTrue(headers.contains("x-request-id"));
            assertTrue(headers.contains("X-REQUEST-ID"));
            assertTrue(headers.contains("X-Version"));
            assertTrue(headers.contains("x-version"));
            assertTrue(headers.contains("Business-Request-Id"));
            assertTrue(headers.contains("business-request-id"));
        }
    }

    @Test
    void shouldIgnoreNullContextDataWhenSerializableContextReturnsNull() {
        SerializableDataContext contextWithNullData = serializableContext(null);
        SerializableDataContext contextWithData = serializableContext(Map.of(
            "X-Nc-Client-Ip", "127.0.0.1"
        ));

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getAll).thenReturn(List.of(contextWithNullData, contextWithData));

            Set<String> headers = provider.getDownstreamHeaders();

            assertTrue(headers.contains("X-Nc-Client-Ip"));
            assertTrue(headers.contains("x-nc-client-ip"));
        }
    }

    @Test
    void shouldIgnoreContextWhenSerializableContextDataReadingFails() {
        SerializableDataContext brokenContext = mock(SerializableDataContext.class);
        SerializableDataContext contextWithData = serializableContext(Map.of(
            "Accept-Language", "en"
        ));

        when(brokenContext.getSerializableContextData()).thenThrow(new RuntimeException("Failed to read context"));

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getAll).thenReturn(List.of(brokenContext, contextWithData));

            Set<String> headers = provider.getDownstreamHeaders();

            assertTrue(headers.contains("Accept-Language"));
            assertTrue(headers.contains("accept-language"));
            assertFalse(headers.contains("Failed to read context"));
        }
    }

    private static SerializableDataContext serializableContext(Map<String, Object> data) {
        SerializableDataContext context = mock(SerializableDataContext.class);
        when(context.getSerializableContextData()).thenReturn(data);
        return context;
    }
}
