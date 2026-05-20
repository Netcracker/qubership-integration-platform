package org.qubership.integration.platform.engine.camel.listeners;

import io.quarkus.arc.All;
import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.support.SimpleEventNotifierSupport;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteAdded;

import java.util.Collection;
import java.util.List;

@Slf4j
@ApplicationScoped
@Unremovable
public class RouteEventListener extends SimpleEventNotifierSupport {
    @Inject
    @All
    @OnRouteAdded
    List<EventProcessingAction<CamelEvent.RouteAddedEvent>> onRouteAddedActions;

    @Inject
    @All
    @OnRouteAdded
    List<EventProcessingAction<CamelEvent.RouteStartedEvent>> onRouteStartedActions;

    @Inject
    @All
    @OnRouteAdded
    List<EventProcessingAction<CamelEvent.RouteStoppedEvent>> onRouteStoppedActions;

    @Inject
    @All
    @OnRouteAdded
    List<EventProcessingAction<CamelEvent.RouteRemovedEvent>> onRouteRemovedActions;

    @Inject
    @All
    @OnRouteAdded
    List<EventProcessingAction<CamelEvent.RouteReloadedEvent>> onRouteReloadedActions;

    @Override
    public void notify(CamelEvent event) throws Exception {
        switch (event) {
            case CamelEvent.RouteAddedEvent ev -> executeActions(ev, onRouteAddedActions);
            case CamelEvent.RouteStartedEvent ev -> executeActions(ev, onRouteStartedActions);
            case CamelEvent.RouteStoppedEvent ev -> executeActions(ev, onRouteStoppedActions);
            case CamelEvent.RouteRemovedEvent ev -> executeActions(ev, onRouteRemovedActions);
            case CamelEvent.RouteReloadedEvent ev -> executeActions(ev, onRouteReloadedActions);
            default -> {
                // do nothing
            }
        }
    }

    private <T extends CamelEvent> void executeActions(
            T event,
            Collection<EventProcessingAction<T>> actions
    ) throws Exception {
        for (var action : actions) {
            action.process(event);
        }
    }
}
