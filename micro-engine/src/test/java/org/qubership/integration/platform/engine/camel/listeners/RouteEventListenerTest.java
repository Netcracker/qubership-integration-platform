package org.qubership.integration.platform.engine.camel.listeners;

import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RouteEventListenerTest {

    @Mock
    private EventProcessingAction<CamelEvent.RouteAddedEvent> routeAddedAction;

    @Mock
    private EventProcessingAction<CamelEvent.RouteAddedEvent> anotherRouteAddedAction;

    @Mock
    private EventProcessingAction<CamelEvent.RouteStartedEvent> routeStartedAction;

    @Mock
    private EventProcessingAction<CamelEvent.RouteStoppedEvent> routeStoppedAction;

    @Mock
    private EventProcessingAction<CamelEvent.RouteRemovedEvent> routeRemovedAction;

    @Mock
    private EventProcessingAction<CamelEvent.RouteReloadedEvent> routeReloadedAction;

    @Mock
    private CamelEvent.RouteAddedEvent routeAddedEvent;

    @Mock
    private CamelEvent.RouteStartedEvent routeStartedEvent;

    @Mock
    private CamelEvent.RouteStoppedEvent routeStoppedEvent;

    @Mock
    private CamelEvent.RouteRemovedEvent routeRemovedEvent;

    @Mock
    private CamelEvent.RouteReloadedEvent routeReloadedEvent;

    @Mock
    private CamelEvent unsupportedEvent;

    private RouteEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new RouteEventListener();
        listener.onRouteAddedActions = List.of();
        listener.onRouteStartedActions = List.of();
        listener.onRouteStoppedActions = List.of();
        listener.onRouteRemovedActions = List.of();
        listener.onRouteReloadedActions = List.of();
    }

    @Test
    void shouldExecuteRouteAddedActionsWhenRouteAddedEventReceived() throws Exception {
        listener.onRouteAddedActions = List.of(routeAddedAction, anotherRouteAddedAction);

        listener.notify(routeAddedEvent);

        InOrder inOrder = inOrder(routeAddedAction, anotherRouteAddedAction);
        inOrder.verify(routeAddedAction).process(routeAddedEvent);
        inOrder.verify(anotherRouteAddedAction).process(routeAddedEvent);
    }

    @Test
    void shouldExecuteRouteStartedActionsWhenRouteStartedEventReceived() throws Exception {
        listener.onRouteStartedActions = List.of(routeStartedAction);

        listener.notify(routeStartedEvent);

        verify(routeStartedAction).process(routeStartedEvent);
    }

    @Test
    void shouldExecuteRouteStoppedActionsWhenRouteStoppedEventReceived() throws Exception {
        listener.onRouteStoppedActions = List.of(routeStoppedAction);

        listener.notify(routeStoppedEvent);

        verify(routeStoppedAction).process(routeStoppedEvent);
    }

    @Test
    void shouldExecuteRouteRemovedActionsWhenRouteRemovedEventReceived() throws Exception {
        listener.onRouteRemovedActions = List.of(routeRemovedAction);

        listener.notify(routeRemovedEvent);

        verify(routeRemovedAction).process(routeRemovedEvent);
    }

    @Test
    void shouldExecuteRouteReloadedActionsWhenRouteReloadedEventReceived() throws Exception {
        listener.onRouteReloadedActions = List.of(routeReloadedAction);

        listener.notify(routeReloadedEvent);

        verify(routeReloadedAction).process(routeReloadedEvent);
    }

    @Test
    void shouldIgnoreUnsupportedEvent() throws Exception {
        listener.onRouteAddedActions = List.of(routeAddedAction);
        listener.onRouteStartedActions = List.of(routeStartedAction);
        listener.onRouteStoppedActions = List.of(routeStoppedAction);
        listener.onRouteRemovedActions = List.of(routeRemovedAction);
        listener.onRouteReloadedActions = List.of(routeReloadedAction);

        listener.notify(unsupportedEvent);

        verifyNoInteractions(
            routeAddedAction,
            routeStartedAction,
            routeStoppedAction,
            routeRemovedAction,
            routeReloadedAction
        );
    }

    @Test
    void shouldPropagateExceptionWhenRouteAddedActionFails() throws Exception {
        RuntimeException exception = new RuntimeException("Failed to process route added event");

        listener.onRouteAddedActions = List.of(routeAddedAction, anotherRouteAddedAction);
        doThrow(exception).when(routeAddedAction).process(routeAddedEvent);

        RuntimeException result = assertThrows(RuntimeException.class, () -> listener.notify(routeAddedEvent));

        assertSame(exception, result);
        verify(anotherRouteAddedAction, never()).process(routeAddedEvent);
    }
}
