package org.qubership.integration.platform.engine.camel.reifiers;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.core.EventConsumer;
import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.component.resilience4j.ResilienceProcessor;
import org.apache.camel.model.CircuitBreakerDefinition;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.model.logging.LogLoggingLevel;
import org.qubership.integration.platform.engine.service.debugger.CamelDebugger;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ROUTE_METADATA_KEY;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class CustomResilienceReifierTest {

    @Test
    void shouldReturnWarnWhenMetadataMissing() throws Exception {
        ResilienceProcessor processor = mock(ResilienceProcessor.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);

        when(processor.getCamelContext()).thenReturn(camelContext);
        when(processor.getRouteId()).thenReturn("route-1");
        when(camelContext.getRoute("route-1")).thenReturn(route);
        when(route.getProperties()).thenReturn(Map.of());

        LogLoggingLevel result = invokeGetLoggingLevel(processor);

        assertEquals(LogLoggingLevel.WARN, result);
    }

    @Test
    void shouldReturnLoggingLevelFromDebuggerWhenMetadataPresent() throws Exception {
        ResilienceProcessor processor = mock(ResilienceProcessor.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Metadata metadata = mock(Metadata.class);
        CamelDebugger debugger = mock(CamelDebugger.class, RETURNS_DEEP_STUBS);

        Map<String, Object> properties = new HashMap<>();
        properties.put(ROUTE_METADATA_KEY, metadata);

        when(processor.getCamelContext()).thenReturn(camelContext);
        when(processor.getRouteId()).thenReturn("route-1");
        when(camelContext.getRoute("route-1")).thenReturn(route);
        when(route.getProperties()).thenReturn(properties);
        when(metadata.getDeploymentId()).thenReturn("deployment-1");
        when(camelContext.getDebugger()).thenReturn(debugger);
        when(debugger.getRelatedProperties("deployment-1")
                .getActualRuntimeProperties()
                .getLogLoggingLevel()).thenReturn(LogLoggingLevel.INFO);

        LogLoggingLevel result = invokeGetLoggingLevel(processor);

        assertEquals(LogLoggingLevel.INFO, result);
    }

    @Test
    void shouldInvokeConsumerWhenLogLevelMatchesPredicate() throws Exception {
        CustomResilienceReifier reifier = reifier();
        ResilienceProcessor processor = processorWithLogLevel(LogLoggingLevel.INFO);
        AtomicBoolean invoked = new AtomicBoolean(false);

        EventConsumer<CircuitBreakerEvent> eventConsumer = invokeCallIfLogLevel(
                reifier,
                processor,
                LogLoggingLevel::isInfoLevel,
                event -> invoked.set(true)
        );

        eventConsumer.consumeEvent(mock(CircuitBreakerEvent.class));

        assertTrue(invoked.get());
    }

    @Test
    void shouldNotInvokeConsumerWhenLogLevelDoesNotMatchPredicate() throws Exception {
        CustomResilienceReifier reifier = reifier();
        ResilienceProcessor processor = processorWithLogLevel(LogLoggingLevel.WARN);
        AtomicBoolean invoked = new AtomicBoolean(false);

        EventConsumer<CircuitBreakerEvent> eventConsumer = invokeCallIfLogLevel(
                reifier,
                processor,
                LogLoggingLevel::isInfoLevel,
                event -> invoked.set(true)
        );

        eventConsumer.consumeEvent(mock(CircuitBreakerEvent.class));

        assertFalse(invoked.get());
    }

    @Test
    void shouldRegisterAllEventConsumersWhenConfigureEventPublisherCalled() throws Exception {
        CustomResilienceReifier reifier = reifier();
        CircuitBreaker.EventPublisher eventPublisher = mock(CircuitBreaker.EventPublisher.class);
        ResilienceProcessor processor = mock(ResilienceProcessor.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);

        invokeConfigureEventPublisher(reifier, eventPublisher, processor, circuitBreaker);

        verify(eventPublisher).onStateTransition(any());
        verify(eventPublisher).onSuccess(any());
        verify(eventPublisher).onError(any());
        verify(eventPublisher).onReset(any());
        verify(eventPublisher).onIgnoredError(any());
        verify(eventPublisher).onCallNotPermitted(any());
        verify(eventPublisher).onFailureRateExceeded(any());
        verify(eventPublisher).onSlowCallRateExceeded(any());
    }

    private static CustomResilienceReifier reifier() {
        Route route = mock(Route.class);
        CircuitBreakerDefinition definition = mock(CircuitBreakerDefinition.class);
        return new CustomResilienceReifier(route, definition);
    }

    private static ResilienceProcessor processorWithLogLevel(LogLoggingLevel level) {
        ResilienceProcessor processor = mock(ResilienceProcessor.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Metadata metadata = mock(Metadata.class);
        CamelDebugger debugger = mock(CamelDebugger.class, RETURNS_DEEP_STUBS);

        Map<String, Object> properties = new HashMap<>();
        properties.put(ROUTE_METADATA_KEY, metadata);

        when(processor.getCamelContext()).thenReturn(camelContext);
        when(processor.getRouteId()).thenReturn("route-1");
        when(camelContext.getRoute("route-1")).thenReturn(route);
        when(route.getProperties()).thenReturn(properties);
        when(metadata.getDeploymentId()).thenReturn("deployment-1");
        when(camelContext.getDebugger()).thenReturn(debugger);
        when(debugger.getRelatedProperties("deployment-1")
                .getActualRuntimeProperties()
                .getLogLoggingLevel()).thenReturn(level);

        return processor;
    }

    private static LogLoggingLevel invokeGetLoggingLevel(ResilienceProcessor processor) throws Exception {
        Method method = CustomResilienceReifier.class.getDeclaredMethod("getLoggingLevel", ResilienceProcessor.class);
        method.setAccessible(true);
        return (LogLoggingLevel) method.invoke(null, processor);
    }

    @SuppressWarnings("unchecked")
    private static EventConsumer<CircuitBreakerEvent> invokeCallIfLogLevel(
            CustomResilienceReifier reifier,
            ResilienceProcessor processor,
            Predicate<LogLoggingLevel> predicate,
            Consumer<CircuitBreakerEvent> consumer
    ) throws Exception {
        Method method = CustomResilienceReifier.class.getDeclaredMethod(
                "callIfLogLevel",
                ResilienceProcessor.class,
                Predicate.class,
                Consumer.class
        );
        method.setAccessible(true);
        return (EventConsumer<CircuitBreakerEvent>) method.invoke(reifier, processor, predicate, consumer);
    }

    private static void invokeConfigureEventPublisher(
            CustomResilienceReifier reifier,
            CircuitBreaker.EventPublisher eventPublisher,
            ResilienceProcessor processor,
            CircuitBreaker circuitBreaker
    ) throws Exception {
        Method method = CustomResilienceReifier.class.getDeclaredMethod(
                "configureEventPublisher",
                CircuitBreaker.EventPublisher.class,
                ResilienceProcessor.class,
                CircuitBreaker.class
        );
        method.setAccessible(true);
        method.invoke(reifier, eventPublisher, processor, circuitBreaker);
    }
}
