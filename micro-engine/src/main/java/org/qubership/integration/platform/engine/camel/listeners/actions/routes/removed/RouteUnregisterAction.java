package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import io.vertx.core.impl.ConcurrentHashSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.qubership.integration.platform.engine.camel.listeners.EventProcessingAction;
import org.qubership.integration.platform.engine.camel.listeners.qualifiers.OnRouteRemoved;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.service.RouteRegistrationService;
import org.qubership.integration.platform.engine.util.InjectUtil;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

@Slf4j
@OnRouteRemoved
@ApplicationScoped
public class RouteUnregisterAction implements EventProcessingAction<CamelEvent.RouteRemovedEvent> {
    private final Set<String> unregisteredRouteChainIds = new ConcurrentHashSet<>();
    private final Optional<RouteRegistrationService> routeRegistrationService;

    @Inject
    public RouteUnregisterAction(
            Instance<RouteRegistrationService> routeRegistrationService
    ) {
        this.routeRegistrationService = InjectUtil.injectOptional(routeRegistrationService);
    }

    @Override
    public void process(CamelEvent.RouteRemovedEvent event) throws Exception {
        Route route = event.getRoute();
        DeploymentInfo deploymentInfo = MetadataUtil.getBean(route, DeploymentInfo.class);
        if (unregisteredRouteChainIds.add(deploymentInfo.getId())) {
            routeRegistrationService.ifPresentOrElse(
                    svc -> {
                        Collection<RouteRegistrationInfo> routeRegistrationInfos =
                                MetadataUtil.getRouteRegistrationInfo(
                                        route.getCamelContext(),
                                        deploymentInfo.getSnapshot().getId()
                                );
                        svc.unregisterRoutes(routeRegistrationInfos);
                    },
                    () -> log.warn("Route deregistration on Control Plane for deployment '{}' ({}) is skipped due to application configuration.",
                            deploymentInfo.getName(), deploymentInfo.getId())
            );
        }
    }
}
