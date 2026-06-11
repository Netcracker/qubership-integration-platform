package org.qubership.integration.platform.engine.camel.context.propagation;

import com.fasterxml.jackson.dataformat.xml.util.CaseInsensitiveNameSet;
import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.ContextProvider;
import com.netcracker.cloud.context.propagation.core.RequestContextPropagation;
import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import com.netcracker.cloud.context.propagation.core.contextdata.OutgoingContextData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CamelExchangeContextPropagationTest {

    @Mock
    private ContextPropsProvider contextPropsProvider;

    private CamelExchangeContextPropagation contextPropagation;

    @BeforeEach
    void setUp() {
        contextPropagation = new CamelExchangeContextPropagation(contextPropsProvider);
    }

    @Test
    void shouldInitializeRequestContextWhenHeadersArePresent() {
        Map<String, Object> headers = Map.of("X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643");

        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            ArgumentCaptor<IncomingContextData> incomingContextDataCaptor =
                ArgumentCaptor.forClass(IncomingContextData.class);

            contextPropagation.initRequestContext(headers);

            requestContextPropagation.verify(RequestContextPropagation::clear);
            requestContextPropagation.verify(() ->
                RequestContextPropagation.initRequestContext(incomingContextDataCaptor.capture()));

            IncomingContextData incomingContextData = incomingContextDataCaptor.getValue();
            assertEquals("0350d916-60f2-4a5f-a7de-2eb154468643", incomingContextData.get("X-Request-Id"));
            assertEquals("0350d916-60f2-4a5f-a7de-2eb154468643", incomingContextData.get("x-request-id"));
        }
    }

    @Test
    void shouldNotThrowWhenRequestContextInitializationFails() {
        Map<String, Object> headers = Map.of("X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643");

        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.initRequestContext(any(IncomingContextData.class)))
                .thenThrow(new RuntimeException("Context initialization failed"));

            assertDoesNotThrow(() -> contextPropagation.initRequestContext(headers));

            requestContextPropagation.verify(RequestContextPropagation::clear);
            requestContextPropagation.verify(() ->
                RequestContextPropagation.initRequestContext(any(IncomingContextData.class)));
        }
    }

    @Test
    void shouldCreateContextSnapshotUsingContextManager() {
        Map<String, Object> snapshot = Map.of("tenant", "default-tenant");

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::createContextSnapshot).thenReturn(snapshot);

            Map<String, Object> result = contextPropagation.createContextSnapshot();

            assertSame(snapshot, result);
        }
    }

    @Test
    void shouldActivateContextSnapshotUsingContextManager() {
        Map<String, Object> snapshot = Map.of("tenant", "default-tenant");

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextPropagation.activateContextSnapshot(snapshot);

            contextManager.verify(() -> ContextManager.activateContextSnapshot(snapshot));
        }
    }

    @Test
    void shouldClearRequestContext() {
        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            contextPropagation.clear();

            requestContextPropagation.verify(RequestContextPropagation::clear);
        }
    }

    @Test
    void shouldReturnSafeContextValueWhenContextExists() {
        TestContext testContext = new TestContext("default-tenant");

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(() -> ContextManager.getSafe("tenant")).thenReturn(Optional.of(testContext));

            String result = contextPropagation.getSafeContext("tenant", TestContext::value);

            assertEquals("default-tenant", result);
        }
    }

    @Test
    void shouldReturnNullWhenSafeContextDoesNotExist() {
        Function<TestContext, String> getter = TestContext::value;

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(() -> ContextManager.getSafe("tenant")).thenReturn(Optional.empty());

            String result = contextPropagation.getSafeContext("tenant", getter);

            assertNull(result);
        }
    }

    @Test
    void shouldNotCallGetterWhenSafeContextDoesNotExist() {
        Function<TestContext, String> getter = anyGetter();

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(() -> ContextManager.getSafe("tenant")).thenReturn(Optional.empty());

            String result = contextPropagation.getSafeContext("tenant", getter);

            assertNull(result);
            verifyNoInteractions(getter);
        }
    }

    @Test
    void shouldPropagateExchangeHeadersToObjectMap() {
        Map<String, Object> headers = new HashMap<>();

        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Request-Id", 12345);
                    outgoingContextData.set(null, "ignored");
                    outgoingContextData.set("X-Null-Value", null);
                    return null;
                });

            contextPropagation.propagateExchangeHeaders(headers);

            assertEquals("12345", headers.get("X-Request-Id"));
            assertFalse(headers.containsKey("X-Null-Value"));
        }
    }

    @Test
    void shouldPropagateHeadersToStringMap() {
        Map<String, String> headers = new HashMap<>();

        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Request-Id", 12345);
                    outgoingContextData.set(null, "ignored");
                    outgoingContextData.set("X-Null-Value", null);
                    return null;
                });

            contextPropagation.propagateHeaders(headers);

            assertEquals(Map.of("X-Request-Id", "12345"), headers);
        }
    }

    @Test
    void shouldBuildContextSnapshotForSessionsWhenPropagationSucceeds() {
        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643");
                    outgoingContextData.set("X-Tenant", "default-tenant");
                    return null;
                });

            Map<String, String> result = contextPropagation.buildContextSnapshotForSessions();

            assertEquals(Map.of(
                "X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643",
                "X-Tenant", "default-tenant"
            ), result);
        }
    }

    @Test
    void shouldReturnEmptyContextSnapshotForSessionsWhenPropagationFails() {
        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenThrow(new RuntimeException("Propagation failed"));

            Map<String, String> result = contextPropagation.buildContextSnapshotForSessions();

            assertEquals(Collections.emptyMap(), result);
        }
    }

    @Test
    void shouldReturnHeadersForCurrentContextCaseInsensitively() {
        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Tenant", "default-tenant");
                    return null;
                });

            Map<String, Object> headers = contextPropagation.getHeadersForCurrentContext();

            assertEquals("default-tenant", headers.get("X-Tenant"));
            assertEquals("default-tenant", headers.get("x-tenant"));
        }
    }

    @Test
    void shouldGetHeadersForContextUsingContextManager() {
        Map<String, Object> context = Map.of("tenant", "default-tenant");

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class);
             MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class)) {
            contextManager.when(() ->
                    ContextManager.executeWithContext(anyMap(), anySupplier()))
                .thenAnswer(invocation -> invocation.<Supplier<Map<String, Object>>>getArgument(1).get());

            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Tenant", "default-tenant");
                    return null;
                });

            Map<String, Object> headers = contextPropagation.getHeadersForContext(context);

            assertEquals("default-tenant", headers.get("X-Tenant"));
            assertEquals("default-tenant", headers.get("x-tenant"));
        }
    }

    @Test
    void shouldRemoveDownstreamContextHeadersFromExchangeHeaders() {
        Map<String, Object> exchangeHeaders = new LinkedHashMap<>();
        exchangeHeaders.put("X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643");
        exchangeHeaders.put("X-Tenant", "default-tenant");
        exchangeHeaders.put("Other-Header", "other-value");

        Set<String> downstreamHeaders = CaseInsensitiveNameSet.construct(Set.of(
            "x-request-id",
            "x-tenant"
        ));

        when(contextPropsProvider.getDownstreamHeaders()).thenReturn(downstreamHeaders);

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getContextProviders).thenReturn(Collections.emptyList());
            contextManager.when(() ->
                    ContextManager.executeWithContext(anyMap(), anySupplier()))
                .thenAnswer(invocation -> invocation.<Supplier<Set<String>>>getArgument(1).get());

            contextPropagation.removeContextHeaders(exchangeHeaders);

            assertEquals(Map.of("Other-Header", "other-value"), exchangeHeaders);
        }
    }

    @Test
    void shouldReturnEmptyContextForHeadersWhenNoContextProvidersExist() {
        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getContextProviders).thenReturn(Collections.emptyList());

            Map<String, Object> context = contextPropagation.getContextForHeaders(
                new CamelExchangeRequestContextData(Map.of("X-Tenant", "default-tenant"))
            );

            assertEquals(Collections.emptyMap(), context);
        }
    }

    @Test
    void shouldBuildContextForHeadersFromNonNullProviderValuesOnly() {
        ContextProvider tenantProvider = contextProvider("tenant", "default-tenant");

        ContextProvider emptyProvider = mock(ContextProvider.class);
        when(emptyProvider.provide(any(IncomingContextData.class))).thenReturn(null);

        try (MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            contextManager.when(ContextManager::getContextProviders)
                .thenReturn(List.of(tenantProvider, emptyProvider));

            Map<String, Object> result = contextPropagation.getContextForHeaders(
                new CamelExchangeRequestContextData(Map.of("X-Tenant", "default-tenant"))
            );

            assertEquals(Map.of("tenant", "default-tenant"), result);
        }
    }

    @Test
    void shouldOverrideHeadersForCurrentContextAndSetResolvedContextValues() {
        Map<String, Object> overrideHeaders = Map.of(
            "X-Tenant", "override-tenant",
            "X-Request-Id", "0350d916-60f2-4a5f-a7de-2eb154468643"
        );

        ContextProvider tenantProvider = mock(ContextProvider.class);
        ContextProvider nullProvider = mock(ContextProvider.class);

        when(tenantProvider.provide(any(IncomingContextData.class)))
            .thenAnswer(invocation -> {
                IncomingContextData incomingContextData = invocation.getArgument(0);
                return incomingContextData.get("X-Tenant");
            });
        when(tenantProvider.contextName()).thenReturn("tenant");

        when(nullProvider.provide(any(IncomingContextData.class))).thenReturn(null);

        try (MockedStatic<RequestContextPropagation> requestContextPropagation =
                 mockStatic(RequestContextPropagation.class);
             MockedStatic<ContextManager> contextManager = mockStatic(ContextManager.class)) {
            requestContextPropagation.when(() ->
                    RequestContextPropagation.populateResponse(any(OutgoingContextData.class)))
                .thenAnswer(invocation -> {
                    OutgoingContextData outgoingContextData = invocation.getArgument(0);
                    outgoingContextData.set("X-Tenant", "current-tenant");
                    outgoingContextData.set("X-Existing-Header", "existing-value");
                    return null;
                });

            contextManager.when(ContextManager::getContextProviders)
                .thenReturn(List.of(tenantProvider, nullProvider));

            contextPropagation.overrideHeadersForCurrentContext(overrideHeaders);

            contextManager.verify(() -> ContextManager.set("tenant", "override-tenant"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Function<TestContext, String> anyGetter() {
        return (Function<TestContext, String>) mock(Function.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> Supplier<T> anySupplier() {
        return any(Supplier.class);
    }

    private record TestContext(String value) {
    }

    private static ContextProvider contextProvider(String contextName, Object contextValue) {
        ContextProvider contextProvider = mock(ContextProvider.class);
        when(contextProvider.provide(any(IncomingContextData.class))).thenReturn(contextValue);
        when(contextProvider.contextName()).thenReturn(contextName);
        return contextProvider;
    }
}
